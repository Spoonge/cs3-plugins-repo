package com.AnimeTitans

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okio.ByteString.Companion.decodeBase64
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL

class AnimeTitans : MainAPI() {
    override var mainUrl = "https://animetitans.com" 
    override var name = "AnimeTitans"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Others )
    override var lang = "ar"
    override val usesWebView = false
    override val hasMainPage = true

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val bsx = select(".bsx")
        val url = bsx.select("a").attr("href")
        val limit = bsx.select(".limit")
        val tt = bsx.select(".tt")
        val episodesText = limit.select(".bt .epx").text()
        val episodesNumber = if(episodesText.contains("الحلقة")){episodesText.getIntFromText()}else{null}
        val title = tt.select("h2").text().replace(episodesText,"")
        val posterUrl = limit.select("img").attr("src")
        val typeText = limit.select(".typez.TV").text()
        val dubSt = title.contains("مدبلج")
        val type =
            if (typeText.contains("أنمي".toRegex())) TvType.Anime
            else if(typeText.contains("أونا|أوفا".toRegex())) TvType.OVA
            else if(typeText.contains("فيلم".toRegex())) TvType.AnimeMovie
            else TvType.Others
        return newAnimeSearchResponse(
            title,
            url,
            type,
        ) {
            addDubStatus(dubSt,!dubSt,episodesNumber,episodesNumber)
            this.posterUrl = posterUrl
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ".toRegex(), "+")
        return app.get("$mainUrl/?s=$q").document
            .select(".bsx").mapNotNull {
                it.toSearchResponse()
            }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/").document
        val homeList = doc.select(".bixbox.bbnofrm").mapNotNull {
                val title = it.select("h3").text()
                val list = it.select(".bsx").mapNotNull {
                            anime -> anime.toSearchResponse()
                    }.distinct()
                HomePageList(title, list, isHorizontalImages = false)
            }
        return newHomePageResponse(homeList, hasNext = false)
    }
    
    private fun Element.toEpisode(): Episode {
        val a = select("li a")
        val url = a.attr("href")
        val epnum = a.select(".epl-num").text()
        val title = a.select(".epl-title").text()
        val date = a.select(".epl-date").text()
        return newEpisode(url) {
            name = title
            episode = epnum.getIntFromText()
            addDate(date)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        val isNotMain = doc.select("div.nvs.nvsc").isNotEmpty()
        val mainLink = doc.select("div.nvs.nvsc a").attr("href")

        doc = when {
            isNotMain -> app.get(mainLink).document
            else -> doc
        }
        
        val infox = doc.select("div .infox")
        val title = infox.select("h1").text()
        val poster = doc.select(".bigcover img").attr("src").ifEmpty { doc.select("div .bigcontent img").attr("src") }
        val description = doc.select("div[itemprop='description']").text()
        val year = doc.select(".info-content .spe span:contains(سنة الإصدار)").text().getIntFromText()
        val rating = doc.select("div.rating strong").text().replace("التقييم","").toRatingInt()
        val typeText = doc.select(".info-content span:contains(النوع)").text()
        val tags = doc.select(".info-content .genxed a").map { it.text() }
        val nameTags = doc.select(".bottom.tags a")
        var engName = title
        
        for(tag in nameTags) {
            engName = when {
                !tag.text().contains("[\u0621-\u064A]".toRegex()) -> tag.text()
                else -> engName
            }
        }
        
        val type =
            if (typeText.contains("أنمي".toRegex())) TvType.Anime
            else if(typeText.contains("أونا|أوفا".toRegex())) TvType.OVA
            else if(typeText.contains("فيلم".toRegex())) TvType.AnimeMovie
            else TvType.Others
        val recommendations = doc.select(".bixbox .listupd article").mapNotNull {
                it.toSearchResponse()
        }
        val episodes = doc.select(".eplister ul li").map {
                it.toEpisode()
            }.let {
                val isReversed = (it.lastOrNull()?.episode ?: 1) < (it.firstOrNull()?.episode ?: 0)
                if (isReversed)
                    it.reversed()
                else it
            }
       
        return newAnimeLoadResponse(title, url, type) {
            this.apiName = this@AnimeTitans.name
            engName = this.engName
            posterUrl = poster
            this.year = year
            addEpisodes(if(title.contains("مدبلج".toRegex())) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            plot = description
            this.rating = rating
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val regex = """src=\"([^"]+)\"""".toRegex()
        var doc = app.get(data).document
        val movieLink = doc.select("div.bigcover a")
        
        doc = when{ 
            movieLink.isNotEmpty() -> app.get(movieLink.attr("href")).document
            else -> doc
        }
        
        doc.select(".mirror.mirror option").map{ option ->
            val encodedbase64 = option.attr("value")
            val decodedbase64 = encodedbase64.decodeBase64().toString()
            val matchResult = regex.find(decodedbase64)
        if (matchResult != null) {
            val sourceLink = matchResult.groupValues.getOrNull(1)
            if (sourceLink != null && sourceLink.isNotEmpty()) {
                loadExtractor(sourceLink, data, subtitleCallback, callback)
                }
            }
        }
        
        doc.select(".player-embed#pembed iframe").attr("src").let { src ->
            app.get(src).document.select("body > script:nth-child(2)").html().substringAfter("""source: """").substringBefore('\"').let{ m3u8 ->
                   if(m3u8 != null){
                            ExtractorLink(
                            name = this.name,
                            source = "${this.name} Cloud",
                            url = m3u8,
                            isM3u8 = true,
                            quality = Qualities.Unknown.value,
                            referer = "$mainUrl/"                     
                            )
                   }
             }
        }
        
        val dllinks = doc.select(".bixbox.mctn .dlbox ul li")
        
        for( dllink in dllinks ) {
           
            val server = dllink.select("span.q").text()
            val quality = dllink.select("span.w").text()
            val link = dllink.select("span.e a").attr("href")
            
            if( server.contains("التحميل المباشر") ) {  
             callback.invoke(
                        ExtractorLink(
                            this.name,
                            "${this.name} Download Source",
                            link,
                            data,
                            quality.getIntFromText() ?: Qualities.Unknown.value,
                        )
                    ) 
                }
            
            }
        return true
    }
}
