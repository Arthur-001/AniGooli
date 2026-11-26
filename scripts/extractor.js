/**
 * AniGooli Remote Extractor Script (ported from pystardust/ani-cli v5.2.0 miruro pipeline).
 * Executes inside AndroidX JavaScriptSandbox or Node.js without external dependencies.
 */
(function (root) {
    "use strict";

    var DEFAULT_CONFIG = {
        miruroBase: "https://miruro.cx",
        userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    };

    function mergeConfig(config) {
        var cfg = config || {};
        return {
            miruroBase: (cfg.miruroBase || DEFAULT_CONFIG.miruroBase).replace(/\/+$/, ""),
            userAgent: cfg.userAgent || DEFAULT_CONFIG.userAgent
        };
    }

    function defaultHeaders(cfg) {
        return {
            "User-Agent": cfg.userAgent
        };
    }

    function buildSearchRequest(query, config) {
        var cfg = mergeConfig(config);
        return {
            url: cfg.miruroBase + "/search?q=" + encodeURIComponent(String(query || "").trim()),
            headers: defaultHeaders(cfg)
        };
    }

    function extractBalancedSlice(text, startIdx, openChar, closeChar) {
        if (startIdx < 0 || startIdx >= text.length) return "";
        var depth = 0;
        var inString = false;
        var quote = "";
        var escaped = false;
        for (var i = startIdx; i < text.length; i++) {
            var ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch === "\\") {
                    escaped = true;
                } else if (ch === quote) {
                    inString = false;
                }
                continue;
            }
            if (ch === '"' || ch === "'") {
                inString = true;
                quote = ch;
            } else if (ch === openChar) {
                depth++;
            } else if (ch === closeChar) {
                depth--;
                if (depth === 0) {
                    return text.slice(startIdx, i + 1);
                }
            }
        }
        return text.slice(startIdx);
    }

    function splitTopLevelObjects(arrayInner) {
        var objects = [];
        var depth = 0;
        var inString = false;
        var quote = "";
        var escaped = false;
        var objStart = -1;
        for (var i = 0; i < arrayInner.length; i++) {
            var ch = arrayInner.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch === "\\") {
                    escaped = true;
                } else if (ch === quote) {
                    inString = false;
                }
                continue;
            }
            if (ch === '"' || ch === "'") {
                inString = true;
                quote = ch;
            } else if (ch === "{") {
                if (depth === 0) objStart = i;
                depth++;
            } else if (ch === "}") {
                depth--;
                if (depth === 0 && objStart >= 0) {
                    objects.push(arrayInner.slice(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return objects;
    }

    function matchStringProp(block, key) {
        var re = new RegExp('(?:^|[,{\\s;])"?(' + key + ')"?\\s*:\\s*"([^"]*)"');
        var m = re.exec(block);
        return m ? m[2] : "";
    }

    function matchArrayFirstString(block, key) {
        var re = new RegExp('(?:^|[,{\\s;])"?(' + key + ')"?\\s*:\\s*\\[\\s*"([^"]+)"');
        var m = re.exec(block);
        return m ? m[2] : "";
    }

    function parseSearchResponse(html, _config) {
        var text = String(html || "");
        var results = [];
        var seen = {};
        var markerMatch = /"?items"?\s*:\s*\[/.exec(text);
        if (!markerMatch) {
            return results;
        }
        var bracketStart = markerMatch.index + markerMatch[0].length - 1;
        var itemsSlice = extractBalancedSlice(text, bracketStart, "[", "]");
        if (itemsSlice.length < 2) {
            return results;
        }
        var itemObjects = splitTopLevelObjects(itemsSlice.slice(1, -1));
        for (var i = 0; i < itemObjects.length; i++) {
            var item = itemObjects[i];
            var providerId = matchStringProp(item, "id");
            if (!providerId || seen[providerId]) continue;
            var extIdx = item.search(/"?external_ids"?\s*:\s*\{/);
            var extSlice = extIdx >= 0
                ? extractBalancedSlice(item, item.indexOf("{", extIdx), "{", "}")
                : item;
            var anilistStr = matchArrayFirstString(extSlice, "anilist");
            var malStr = matchArrayFirstString(extSlice, "mal");
            var titleIdx = item.search(/"?title"?\s*:\s*\{/);
            var titleSlice = titleIdx >= 0
                ? extractBalancedSlice(item, item.indexOf("{", titleIdx), "{", "}")
                : item;
            var engTitle = matchStringProp(titleSlice, "english");
            var romTitle = matchStringProp(titleSlice, "romaji");
            var natTitle = matchStringProp(titleSlice, "native");
            var title = engTitle || romTitle || natTitle;
            if (!title) continue;
            var anilistId = anilistStr ? parseInt(anilistStr, 10) : null;
            var malId = malStr ? parseInt(malStr, 10) : null;
            seen[providerId] = true;
            results.push({
                providerId: providerId,
                anilistId: isNaN(anilistId) ? null : anilistId,
                malId: isNaN(malId) ? null : malId,
                title: title,
                englishTitle: engTitle || null,
                romajiTitle: romTitle || null
            });
        }
        return results;
    }

    function buildEpisodesRequest(providerId, config) {
        var cfg = mergeConfig(config);
        return {
            url: cfg.miruroBase + "/watch/" + encodeURIComponent(String(providerId)) + "/x?ep=1",
            headers: defaultHeaders(cfg)
        };
    }

    function parseEpisodesResponse(html, _config) {
        var text = String(html || "");
        var seen = {};
        var nums = [];
        var re = /(?:[,{;]"?number"?\s*:|\.number\s*=)\s*(\d+(?:\.\d+)?)/g;
        var m;
        while ((m = re.exec(text)) !== null) {
            var raw = m[1];
            if (!seen[raw]) {
                seen[raw] = true;
                nums.push(raw);
            }
        }
        nums.sort(function (a, b) {
            return parseFloat(a) - parseFloat(b);
        });
        return nums;
    }

    function buildMediaRequest(providerId, episode, _mode, config) {
        var cfg = mergeConfig(config);
        return {
            url: cfg.miruroBase + "/watch/" + encodeURIComponent(String(providerId)) + "/x?ep=" + encodeURIComponent(String(episode)),
            headers: defaultHeaders(cfg)
        };
    }

    function pickEnglishSubtitle(provBlock, hoisted) {
        var subArrIdx = provBlock.search(/"?subtitles"?\s*:\s*\[/);
        var candidateSubText = "";
        if (subArrIdx >= 0) {
            candidateSubText = extractBalancedSlice(provBlock, provBlock.indexOf("[", subArrIdx), "[", "]");
        } else {
            var varMatch = /"?subtitles"?\s*:\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\s*[,}]/.exec(provBlock);
            if (varMatch && hoisted) {
                var varName = varMatch[1].replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
                var hoistedRe = new RegExp(varName + "\\[\\d+\\]\\s*=\\s*\\{[^;]*", "g");
                var matches = hoisted.match(hoistedRe);
                if (matches) {
                    candidateSubText = "[" + matches.join(",") + "]";
                }
            }
        }
        if (!candidateSubText) return null;
        var subObjects = splitTopLevelObjects(candidateSubText.slice(1, -1));
        var fallbackEn = null;
        for (var i = 0; i < subObjects.length; i++) {
            var obj = subObjects[i];
            var lang = matchStringProp(obj, "language") || matchStringProp(obj, "lang");
            var label = matchStringProp(obj, "label");
            var file = matchStringProp(obj, "file") || matchStringProp(obj, "url");
            if (!file) continue;
            var isEn = /^en(?:[-_][a-zA-Z]+|g)?$/i.test(lang) ||
                /english/i.test(lang) ||
                /english/i.test(label);
            if (!isEn) continue;
            var isDefault = /(?:^|[,{\s])"?default"?\s*:\s*true(?:[,}\s]|$)/.test(obj);
            if (isDefault) return file;
            if (!fallbackEn) fallbackEn = file;
        }
        return fallbackEn;
    }

    function deriveOrigin(urlStr) {
        var m = /^(https?:\/\/[^/]+)/i.exec(String(urlStr || ""));
        return m ? m[1] : "";
    }

    function parseMediaResponse(html, mode, _config) {
        var text = String(html || "");
        var tracksIdx = text.search(/"?tracks"?\s*:\s*\[/);
        if (tracksIdx < 0) {
            return { candidates: [] };
        }
        var hoisted = text.slice(0, tracksIdx);
        var tracksSlice = extractBalancedSlice(text, text.indexOf("[", tracksIdx), "[", "]");
        var trackObjects = splitTopLevelObjects(tracksSlice.slice(1, -1));
        var wanted = (String(mode || "sub").toLowerCase() === "dub")
            ? ["dub"]
            : ["ssub", "sub"];

        var candidates = [];
        for (var w = 0; w < wanted.length; w++) {
            var wantedKind = wanted[w];
            var matchedTrack = null;
            for (var t = 0; t < trackObjects.length; t++) {
                var tKind = matchStringProp(trackObjects[t], "track");
                if (tKind === wantedKind) {
                    matchedTrack = trackObjects[t];
                    break;
                }
            }
            if (!matchedTrack) continue;

            var provIdx = matchedTrack.search(/"?providers"?\s*:\s*\[/);
            var provSlice = provIdx >= 0
                ? extractBalancedSlice(matchedTrack, matchedTrack.indexOf("[", provIdx), "[", "]")
                : matchedTrack;
            var provObjects = splitTopLevelObjects(provSlice.slice(1, -1));
            if (provObjects.length === 0) {
                provObjects = [matchedTrack];
            }

            for (var p = 0; p < provObjects.length; p++) {
                var prov = provObjects[p];
                var subUrl = pickEnglishSubtitle(prov, hoisted);
                var srvIdx = prov.search(/"?servers"?\s*:\s*\[/);
                var srvSlice = srvIdx >= 0
                    ? extractBalancedSlice(prov, prov.indexOf("[", srvIdx), "[", "]")
                    : prov;
                var srvObjects = splitTopLevelObjects(srvSlice.slice(1, -1));
                if (srvObjects.length === 0) {
                    srvObjects = [prov];
                }

                for (var s = 0; s < srvObjects.length; s++) {
                    var srv = srvObjects[s];
                    var serverName = matchStringProp(srv, "server") || matchStringProp(srv, "name") || "Server";
                    var referer = matchStringProp(srv, "Referer") || matchStringProp(srv, "referer");
                    var origin = matchStringProp(srv, "Origin") || matchStringProp(srv, "origin") || deriveOrigin(referer);
                    var streamsIdx = srv.search(/"?streams"?\s*:\s*\[/);
                    if (streamsIdx < 0) continue;
                    var streamsSlice = extractBalancedSlice(srv, srv.indexOf("[", streamsIdx), "[", "]");
                    var streamObjects = splitTopLevelObjects(streamsSlice.slice(1, -1));

                    for (var k = 0; k < streamObjects.length; k++) {
                        var st = streamObjects[k];
                        var streamUrl = matchStringProp(st, "url");
                        if (!streamUrl) continue;
                        var fmt = matchStringProp(st, "format").toLowerCase();
                        var quality = matchStringProp(st, "quality") || "default";
                        candidates.push({
                            server: serverName,
                            url: streamUrl,
                            format: fmt.indexOf("hls") >= 0 || /\.m3u8(\?|$)/i.test(streamUrl) ? "hls" : "mp4",
                            quality: quality,
                            referer: referer,
                            origin: origin,
                            subtitleUrl: subUrl
                        });
                    }
                }
            }
            if (candidates.length > 0) break;
        }
        return { candidates: candidates };
    }

    function resolveUrl(uri, baseUrl) {
        var u = String(uri || "").trim();
        if (!u) return "";
        if (/^https?:\/\//i.test(u)) return u;
        if (u.indexOf("//") === 0) return "https:" + u;
        var origin = deriveOrigin(baseUrl);
        if (u.charAt(0) === "/") {
            return origin + u;
        }
        var baseWithoutQuery = String(baseUrl || "").split("?")[0];
        var lastSlash = baseWithoutQuery.lastIndexOf("/");
        var baseDir = lastSlash >= 0 ? baseWithoutQuery.slice(0, lastSlash + 1) : origin + "/";
        return baseDir + u;
    }

    function parseM3u8(playlistText, baseUrl) {
        var text = String(playlistText || "").replace(/\r/g, "");
        if (text.indexOf("#EXTM3U") < 0) {
            return null;
        }
        var lines = text.split("\n");
        var audioUrl = "";
        var fallbackAudio = "";
        var variants = [];

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line) continue;
            if (line.indexOf("#EXT-X-MEDIA:") === 0 && line.indexOf("TYPE=AUDIO") >= 0) {
                var uriMatch = /URI="([^"]*)"/.exec(line);
                if (uriMatch && uriMatch[1]) {
                    var resolvedAudio = resolveUrl(uriMatch[1], baseUrl);
                    if (line.indexOf("DEFAULT=YES") >= 0 && !audioUrl) {
                        audioUrl = resolvedAudio;
                    } else if (!fallbackAudio) {
                        fallbackAudio = resolvedAudio;
                    }
                }
            } else if (line.indexOf("#EXT-X-STREAM-INF:") === 0) {
                var resMatch = /RESOLUTION=\d+x(\d+)/.exec(line);
                var height = resMatch ? parseInt(resMatch[1], 10) : 0;
                var quality = height > 0 ? (height + "p") : "default";
                var j = i + 1;
                while (j < lines.length) {
                    var nextLine = lines[j].trim();
                    if (nextLine && nextLine.charAt(0) !== "#") {
                        variants.push({
                            quality: quality,
                            height: height,
                            url: resolveUrl(nextLine, baseUrl)
                        });
                        i = j;
                        break;
                    }
                    j++;
                }
            }
        }

        if (variants.length === 0) {
            variants.push({
                quality: "default",
                height: 0,
                url: String(baseUrl || "")
            });
        } else {
            variants.sort(function (a, b) {
                return b.height - a.height;
            });
        }

        return {
            audioUrl: audioUrl || fallbackAudio || null,
            variants: variants
        };
    }

    var api = {
        version: "1.0.0",
        buildSearchRequest: buildSearchRequest,
        parseSearchResponse: parseSearchResponse,
        buildEpisodesRequest: buildEpisodesRequest,
        parseEpisodesResponse: parseEpisodesResponse,
        buildMediaRequest: buildMediaRequest,
        parseMediaResponse: parseMediaResponse,
        parseM3u8: parseM3u8
    };

    root.AniGooliExtractor = api;
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    }
})(typeof globalThis !== "undefined" ? globalThis : this);
