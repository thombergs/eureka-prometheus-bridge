package de.adesso.eurekaprometheusbridge

import khttp.get
import khttp.responses.Response
import org.json.JSONException
import org.json.JSONObject
import org.json.XML
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScheduledJobs(
        @Autowired var gen: Generator,
        @Value("\${bridge.eureka.port}") var eureka_port: String,
        @Value("\${bridge.eureka.host}") var eureka_host: String,
        @Value("\${bridge.eureka.apipath}") var eureka_api_path: String,
        @Value("\${bridge.show.eurekajson}") var show_eureka_json: Boolean
) {

    companion object {
        val log = LoggerFactory.getLogger(ScheduledJobs::class.java.name)
    }

    var configEntries = ArrayList<ConfigEntry>()

    /**Queries Eureka for all App-Data*/
    @Scheduled(fixedRate = 10000)
    fun queryEureka() {
        configEntries.clear()

        log.info("Query Eureka ...")
        var r: Response?
        try {
            r = get(eureka_host + ":" + eureka_port + eureka_api_path)
        } catch (e: Exception) {
            log.info("Requesting Eureka failed!... Trying again in some time.")
            return
        }
        if (r.statusCode == 200) {
            log.info("Found Eureka")
            log.info("Status: " + r.statusCode)
            //Convert xml tto JSON
            val JSONObjectFromXML = XML.toJSONObject(r.text)
            if (show_eureka_json) {
                val jsonPrettyPrintString = JSONObjectFromXML.toString(4)
                log.info(""""
                ${jsonPrettyPrintString}
                """)
            }
            //If JSON is too short no app is registered
            if (JSONObjectFromXML.toString().length < 60) {
                log.error("JSON too short, no app registered with eureka.")
                return
            }
            //Is it one object or an array?
            var isArray = false
            try {
                if (JSONObjectFromXML.getJSONObject("applications").getJSONObject("application") is JSONObject) {
                    isArray = false

                }
            } catch (e: JSONException) {
                isArray = true
            }
            if (!isArray) {
                var name = JSONObjectFromXML.getJSONObject("applications").getJSONObject("application").get("name").toString()
                var hostname = JSONObjectFromXML.getJSONObject("applications").getJSONObject("application").getJSONObject("instance").get("hostName")
                var port = JSONObjectFromXML.getJSONObject("applications").getJSONObject("application").getJSONObject("instance").getJSONObject("port").get("content")
                var targeturl = (hostname.toString() + ":" + port.toString())
                log.info("Found property: $name with targeturl: $targeturl")
                configEntries.add(ConfigEntry(name = name, targeturl = targeturl))
            } else if (isArray) {
                log.info("Found multiple Objects:")
                for (o in JSONObjectFromXML.getJSONObject("applications").getJSONArray("application")) {
                    if (o is JSONObject) {
                        var name = o.get("name").toString()
                        var hostname = o.getJSONObject("instance").get("hostName")
                        var port = o.getJSONObject("instance").getJSONObject("port").get("content")
                        var targeturl = (hostname.toString() + ":" + port.toString())
                        log.info(""" Found Service: $name with targeturl: $targeturl
                            """.trimIndent())
                        configEntries.add(ConfigEntry(name = name, targeturl = targeturl))
                    }
                }
            }
        } else {
            log.error("""No Eureka-Clients found
            Status: ${r.statusCode}
            Text:
            ${XML.toJSONObject(r.text).toString(4)}
            """)
        }
    }

    /**Attempts to generate a new Config-File*/
    @Scheduled(fixedRate = 10000, initialDelay = 5000)
    fun generateConfigFile() {
        log.info("Generating Config File ...")

        log.info("All Entries in Database:")
        for (e in configEntries) {
            log.info(e.toString())
        }
        gen.generatePrometheusConfig(configEntries)
    }

}