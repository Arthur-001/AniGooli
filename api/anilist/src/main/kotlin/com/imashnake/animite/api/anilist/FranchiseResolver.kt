package com.imashnake.animite.api.anilist

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.media.Media.Format.Companion.sanitize
import com.imashnake.animite.api.anilist.sanitize.media.Media.Relation.Companion.sanitize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

private const val MAX_BFS_ROUNDS = 3
private const val MAX_FRANCHISE_NODES = 40
private const val UNKNOWN_YEAR = 9999
private const val UNKNOWN_MONTH = 13
private const val UNKNOWN_DAY = 32

private val franchiseRelations = setOf(
    Media.Relation.PREQUEL,
    Media.Relation.SEQUEL,
    Media.Relation.PARENT,
    Media.Relation.SIDE_STORY,
    Media.Relation.SUMMARY,
    Media.Relation.ALTERNATIVE,
    Media.Relation.SPIN_OFF,
    Media.Relation.ADAPTATION,
    Media.Relation.SOURCE,
)

/**
 * Normalizes anime/manga titles, extracts sequence numbers, and checks whether two media entries
 * belong to the same franchise by title (used to safely admit `OTHER` sequels like
 * `Cyberpunk: Edgerunners` -> `Cyberpunk: Edgerunners 2` while rejecting unrelated crossovers).
 */
internal object FranchiseTitleMatcher {

    private val seasonKeywordRegex = Regex(
        """(?i)\s*[-:–—]?\s*(?:the\s+)?(?:season|series|part|cour)\s*(\d+)\s*$"""
    )
    private val ordinalSeasonRegex = Regex(
        """(?i)\s*[-:–—]?\s*(\d+)(?:st|nd|rd|th)\s+season\s*$"""
    )
    private val shortSeasonRegex = Regex("""\s*[-:–—]?\s*S(\d{1,2})\s*$""")
    private val romanSuffixRegex = Regex("""\s*[-:–—]?\s*(II|III|IV|V|VI|VII|VIII|IX|X)\s*$""")
    private val trailingNumberRegex = Regex("""\s+(\d{1,2})\s*$""")
    private val mediaSuffixRegex = Regex(
        """(?i)\s*[-:–—]?\s*(?:the\s+movie|movie|ova|specials?)\s*$"""
    )
    private val nonWordRegex = Regex("""[^\p{L}\p{N}\s]+""")
    private val multiSpaceRegex = Regex("""\s+""")

    private val romanValues = mapOf(
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
    )

    /** Extracts a trailing season/part/sequence number from [title], if present. */
    fun extractSequenceNumber(title: String): Int? {
        val trimmed = title.trim()
        seasonKeywordRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        ordinalSeasonRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        shortSeasonRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        romanSuffixRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { roman ->
            romanValues[roman]?.let { return it }
        }
        val numMatch = trailingNumberRegex.find(trimmed)
        if (numMatch != null) {
            val prefix = trimmed.substring(0, numMatch.range.first).trim()
            if (prefix.length >= 3) {
                numMatch.groupValues[1].toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    /** Returns the best-effort sequence number across all known titles of [media]. */
    fun sequenceNumber(media: Media.Small): Int? {
        val titles = listOfNotNull(media.englishTitle, media.romajiTitle, media.title)
        return titles.firstNotNullOfOrNull { extractSequenceNumber(it) }
    }

    /** Strips trailing season/part/numbering/format suffixes and normalizes punctuation. */
    fun baseTitle(title: String): String {
        var s = title.trim()
        var changed = true
        while (changed) {
            changed = false
            for (regex in listOf(seasonKeywordRegex, ordinalSeasonRegex, shortSeasonRegex, romanSuffixRegex, mediaSuffixRegex)) {
                val next = regex.replace(s, "").trim()
                if (next != s && next.length >= 3) {
                    s = next
                    changed = true
                }
            }
            val numMatch = trailingNumberRegex.find(s)
            if (numMatch != null) {
                val prefix = s.substring(0, numMatch.range.first).trim()
                if (prefix.length >= 3) {
                    s = prefix
                    changed = true
                }
            }
        }
        return nonWordRegex.replace(s.lowercase(), " ")
            .let { multiSpaceRegex.replace(it, " ") }
            .trim()
    }

    /** Returns true when [a] and [b] share the same base title or a substantial word-boundary prefix. */
    fun areRelated(a: Media.Small, b: Media.Small): Boolean {
        val titlesA = listOfNotNull(a.title, a.englishTitle, a.romajiTitle, a.nativeTitle)
            .map(::baseTitle)
            .filter { it.isNotEmpty() }
            .distinct()
        val titlesB = listOfNotNull(b.title, b.englishTitle, b.romajiTitle, b.nativeTitle)
            .map(::baseTitle)
            .filter { it.isNotEmpty() }
            .distinct()

        for (ba in titlesA) {
            for (bb in titlesB) {
                if (ba == bb) return true
                val (shorter, longer) = if (ba.length <= bb.length) ba to bb else bb to ba
                if (shorter.length >= 5 && longer.startsWith("$shorter ")) {
                    return true
                }
            }
        }
        return false
    }
}

/** Graph node holding a media summary and its outgoing AniList relation edges. */
data class FranchiseNode(
    /** Sanitized media summary. */
    val media: Media.Small,
    /** Direct relation type relative to its parent discovery edge, if any. */
    val relationType: Media.Relation? = null,
    /** Outgoing edges `(relationType, targetMedia)` on this node. */
    val edges: List<Pair<Media.Relation?, Media.Small>> = emptyList(),
)

/** One item inside an organized franchise section. */
data class FranchiseEntry(
    /** Media summary for display and navigation. */
    val media: Media.Small,
    /** Relation label if known (e.g. PREQUEL, SEQUEL, OVA). */
    val relationType: Media.Relation?,
    /** True when this entry corresponds to the currently opened anime/manga page. */
    val isCurrent: Boolean,
    /** 1-based watch order index inside its section. */
    val orderIndex: Int,
)

/** Structured franchise relations grouped into ordered Main Seasons & Movies, OVAs, and Mangas. */
data class OrganizedFranchise(
    /** Main TV seasons and theatrical movies ordered topologically (Prequel -> Sequel) & chronologically. */
    val mainSeasonsAndMovies: List<FranchiseEntry>,
    /** Separate chronological section for OVAs, Specials, and ONAs. */
    val ovasAndSpecials: List<FranchiseEntry>,
    /** Separate section for Mangas, Light Novels, and One-Shots ordered oldest <-> newest. */
    val mangas: List<FranchiseEntry>,
)

/** Pure graph organizer separating and sorting a franchise into Main Seasons/Movies, OVAs, and Mangas. */
object FranchiseOrganizer {

    private val mainFormats = setOf(Media.Format.TV, Media.Format.TV_SHORT, Media.Format.MOVIE)
    private val ovaFormats = setOf(Media.Format.OVA, Media.Format.SPECIAL, Media.Format.ONA)
    private val mangaFormats = setOf(Media.Format.MANGA, Media.Format.ONE_SHOT, Media.Format.NOVEL)

    private val chronologicalComparator: Comparator<Media.Small> = compareBy(
        { it.startYear ?: it.seasonYear ?: UNKNOWN_YEAR },
        { it.startMonth ?: UNKNOWN_MONTH },
        { it.startDay ?: UNKNOWN_DAY },
        { FranchiseTitleMatcher.sequenceNumber(it) ?: Int.MAX_VALUE },
        { it.id },
    )

    /** Organizes [nodesById] around [rootMedia] with topological + chronological watch ordering. */
    fun organize(
        rootMedia: Media.Small,
        nodesById: Map<Int, FranchiseNode>,
        mangaNewestFirst: Boolean,
    ): OrganizedFranchise {
        val allNodes = LinkedHashMap<Int, FranchiseNode>()
        allNodes[rootMedia.id] = nodesById[rootMedia.id] ?: FranchiseNode(rootMedia)
        nodesById.values.forEach { node ->
            allNodes.putIfAbsent(node.media.id, node)
            node.edges.forEach { (rel, target) ->
                if (target.format != Media.Format.MUSIC &&
                    (rel in franchiseRelations ||
                        (rel == Media.Relation.OTHER && FranchiseTitleMatcher.areRelated(node.media, target)))
                ) {
                    allNodes.putIfAbsent(target.id, FranchiseNode(media = target, relationType = rel))
                }
            }
        }

        // Track ONAs explicitly marked as SIDE_STORY (e.g. chibi shorts) so they stay in OVAs & Specials.
        val sideStoryIds = HashSet<Int>()
        allNodes.values.forEach { node ->
            if (node.relationType == Media.Relation.SIDE_STORY) {
                sideStoryIds.add(node.media.id)
            }
            node.edges.forEach { (rel, target) ->
                if (rel == Media.Relation.SIDE_STORY) {
                    sideStoryIds.add(target.id)
                }
            }
        }

        val hasStandardMainFormat = allNodes.values.any { node ->
            node.media.type == Media.Small.Type.ANIME &&
                (node.media.format in mainFormats || node.media.format == null)
        }

        val mainNodes = allNodes.values.filter { node ->
            val fmt = node.media.format
            if (node.media.type != Media.Small.Type.ANIME) return@filter false
            when {
                fmt in mainFormats || fmt == null -> true
                fmt == Media.Format.ONA -> !hasStandardMainFormat || node.media.id !in sideStoryIds
                else -> false
            }
        }
        val mainIds = mainNodes.map { it.media.id }.toSet()
        val orderedMain = topologicalChronologicalSort(mainNodes, allNodes).mapIndexed { idx, node ->
            FranchiseEntry(
                media = node.media,
                relationType = node.relationType,
                isCurrent = node.media.id == rootMedia.id,
                orderIndex = idx + 1,
            )
        }

        val orderedOvas = allNodes.values
            .filter {
                it.media.type == Media.Small.Type.ANIME &&
                    it.media.format in ovaFormats &&
                    it.media.id !in mainIds
            }
            .sortedWith { a, b -> chronologicalComparator.compare(a.media, b.media) }
            .mapIndexed { idx, node ->
                FranchiseEntry(
                    media = node.media,
                    relationType = node.relationType,
                    isCurrent = node.media.id == rootMedia.id,
                    orderIndex = idx + 1,
                )
            }

        val sortedMangasBase = allNodes.values
            .filter { it.media.type == Media.Small.Type.MANGA || it.media.format in mangaFormats }
            .sortedWith { a, b -> chronologicalComparator.compare(a.media, b.media) }
        val finalMangas = if (mangaNewestFirst) sortedMangasBase.reversed() else sortedMangasBase
        val orderedMangas = finalMangas.mapIndexed { idx, node ->
            FranchiseEntry(
                media = node.media,
                relationType = node.relationType,
                isCurrent = node.media.id == rootMedia.id,
                orderIndex = idx + 1,
            )
        }

        return OrganizedFranchise(
            mainSeasonsAndMovies = orderedMain,
            ovasAndSpecials = orderedOvas,
            mangas = orderedMangas,
        )
    }

    private fun topologicalChronologicalSort(
        subset: List<FranchiseNode>,
        allNodes: Map<Int, FranchiseNode>,
    ): List<FranchiseNode> {
        if (subset.size <= 1) return subset
        val subsetIds = subset.map { it.media.id }.toSet()
        val nodeMap = subset.associateBy { it.media.id }
        val adj = HashMap<Int, MutableSet<Int>>()
        val inDegree = HashMap<Int, Int>()
        subsetIds.forEach { id ->
            adj[id] = LinkedHashSet()
            inDegree[id] = 0
        }

        // Build directed watch-order edges: PREQUEL -> current -> SEQUEL
        for (id in subsetIds) {
            val fullNode = allNodes[id] ?: continue
            for ((rel, target) in fullNode.edges) {
                if (target.id !in subsetIds || target.id == id) continue
                when (rel) {
                    Media.Relation.SEQUEL -> {
                        if (adj[id]?.add(target.id) == true) {
                            inDegree[target.id] = (inDegree[target.id] ?: 0) + 1
                        }
                    }
                    Media.Relation.PREQUEL -> {
                        if (adj[target.id]?.add(id) == true) {
                            inDegree[id] = (inDegree[id] ?: 0) + 1
                        }
                    }
                    else -> {}
                }
            }
        }

        val readyQueue = PriorityQueue<FranchiseNode> { a, b ->
            chronologicalComparator.compare(a.media, b.media)
        }
        inDegree.filterValues { it == 0 }.keys.forEach { id ->
            nodeMap[id]?.let { readyQueue.add(it) }
        }

        val ordered = ArrayList<FranchiseNode>(subset.size)
        val visited = HashSet<Int>(subset.size)
        while (readyQueue.isNotEmpty()) {
            val current = readyQueue.poll() ?: break
            if (!visited.add(current.media.id)) continue
            ordered.add(current)
            adj[current.media.id]?.forEach { nextId ->
                val updatedDeg = (inDegree[nextId] ?: 1) - 1
                inDegree[nextId] = updatedDeg
                if (updatedDeg == 0 && nextId !in visited) {
                    nodeMap[nextId]?.let { readyQueue.add(it) }
                }
            }
        }

        // Append any remaining nodes involved in circular AniList metadata chronologically
        if (ordered.size < subset.size) {
            subset
                .filter { it.media.id !in visited }
                .sortedWith { a, b -> chronologicalComparator.compare(a.media, b.media) }
                .forEach { ordered.add(it) }
        }
        return ordered
    }
}

/**
 * Resolves full multi-hop AniList franchise graphs with batched `id_in` queries and an in-memory cache.
 */
class FranchiseResolver(
    private val apolloClient: ApolloClient,
) {
    private val cache = ConcurrentHashMap<String, Map<Int, FranchiseNode>>()
    private val expansionRelations = setOf(
        Media.Relation.PREQUEL,
        Media.Relation.SEQUEL,
        Media.Relation.PARENT,
        Media.Relation.SOURCE,
        Media.Relation.SIDE_STORY,
    )

    /** Resolves and organizes the complete franchise for [rootMedia]. */
    suspend fun resolveFranchise(
        rootMedia: Media,
        language: Media.Language,
        mangaNewestFirst: Boolean,
    ): OrganizedFranchise = withContext(Dispatchers.IO) {
        val rootSmall = Media.Small(
            id = rootMedia.id,
            type = if (rootMedia.mediaFormat in setOf(Media.Format.MANGA, Media.Format.NOVEL, Media.Format.ONE_SHOT)) {
                Media.Small.Type.MANGA
            } else {
                Media.Small.Type.ANIME
            },
            title = rootMedia.title,
            coverImage = rootMedia.coverImage,
            format = rootMedia.mediaFormat,
            episodes = rootMedia.totalEpisodes,
            seasonYear = rootMedia.year?.toIntOrNull(),
            startYear = rootMedia.startYear,
            startMonth = rootMedia.startMonth,
            startDay = rootMedia.startDay,
            englishTitle = rootMedia.englishTitle,
            romajiTitle = rootMedia.romajiTitle,
            nativeTitle = rootMedia.nativeTitle,
        )

        val cacheKey = "${rootMedia.id}_${language.name}"
        val cachedNodes = cache[cacheKey]
        if (cachedNodes != null) {
            return@withContext FranchiseOrganizer.organize(rootSmall, cachedNodes, mangaNewestFirst)
        }

        val collected = LinkedHashMap<Int, FranchiseNode>()
        collected[rootMedia.id] = FranchiseNode(
            media = rootSmall,
            edges = rootMedia.relations,
        )

        var frontier = rootMedia.relations
            .filter { (rel, target) ->
                target.id != rootMedia.id &&
                    target.format != Media.Format.MUSIC &&
                    (rel in franchiseRelations ||
                        (rel == Media.Relation.OTHER && FranchiseTitleMatcher.areRelated(rootSmall, target)))
            }
            .map { it.second.id }
            .distinct()

        var round = 0
        while (frontier.isNotEmpty() && round < MAX_BFS_ROUNDS && collected.size < MAX_FRANCHISE_NODES) {
            val toFetch = frontier.filter { it !in collected }.take(MAX_FRANCHISE_NODES - collected.size)
            if (toFetch.isEmpty()) break
            val fetchedNodes = fetchBatch(toFetch, language)
            if (fetchedNodes.isEmpty()) break

            val nextFrontier = LinkedHashSet<Int>()
            for (node in fetchedNodes) {
                collected[node.media.id] = node
                for ((rel, target) in node.edges) {
                    if (target.id !in collected &&
                        target.format != Media.Format.MUSIC &&
                        (rel in expansionRelations ||
                            (rel == Media.Relation.OTHER && FranchiseTitleMatcher.areRelated(node.media, target)))
                    ) {
                        nextFrontier.add(target.id)
                    }
                }
            }
            frontier = nextFrontier.toList()
            round++
        }

        cache[cacheKey] = collected
        FranchiseOrganizer.organize(rootSmall, collected, mangaNewestFirst)
    }

    private suspend fun fetchBatch(
        ids: List<Int>,
        language: Media.Language,
    ): List<FranchiseNode> = runCatching {
        val response = apolloClient.query(
            MediaFranchiseBatchQuery(ids = Optional.present(ids))
        )
            .fetchPolicy(FetchPolicy.CacheFirst)
            .execute()
        response.data?.page?.media.orEmpty().filterNotNull().map { m ->
            val small = Media.Small(m.mediaSmall, language)
            val edges = m.relations?.edges.orEmpty().mapNotNull { edge ->
                edge?.node?.mediaSmall?.let { target ->
                    edge.relationType?.sanitize() to Media.Small(target, language)
                }
            }
            FranchiseNode(media = small, edges = edges)
        }
    }.getOrDefault(emptyList())
}
