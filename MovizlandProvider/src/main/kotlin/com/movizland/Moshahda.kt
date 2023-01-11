package com.movizland

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.app

open class Moshahda : ExtractorApi() {
    override val name = "Moshahda"
    override val mainUrl = "https://moshahda.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        val regcode = """$mainUrl/embed-(\w+)""".toRegex()
        val code = regcode.find(url)?.groupValues?.getOrNull(1)
        val moshlink = "$mainUrl/embed-$code.html?"
        app.get(moshlink).document.select("html > body > script").let { script ->
        val text = script?.html() ?: ""
        val m3link = text.substringAfter("""fileType: "m3u8", file: """").substringBefore('\"')
        mutableMapOf(
        "l" to 240,
        "n" to 360,
        "h" to 480,
        "x" to 720,
        "o" to 1080,
        ).forEach{ (letter,qual) ->       
                if (m3link.isNotBlank()) {
                    sources.add(
                        ExtractorLink(
                            name = name,
                            source = name,
                            url = m3link.replace("[lnhxo,]+.urlset".toRegex(),letter).replace("master.m3u8","index-v1-a1.m3u8"),
                            isM3u8 = true,
                            quality = qual ?: Qualities.Unknown.value,
                            referer = "$mainUrl/"
                        )
                    )
                  }
            }
        }
        return sources
    }
}
