/**
 *  EyezOn UNO Zone Driver - Carbon Monoxide
 *  Version: 1.0.4
 *
 *  Child device for a zone configured as a CO detector. State is pushed in by the
 *  parent app via parse() whenever the connection driver reports a zone event.
 */
metadata {
    definition(name: "EyezOn UNO Zone CO", namespace: "eyezonUno", author: "babgvant") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "CarbonMonoxideDetector"
    }
}

def installed() {
    sendEvent(name: "carbonMonoxide", value: "clear")
}

def parse(List<Map> events) {
    events?.each { Map evt -> sendEvent(evt) }
}

def refresh() {
    parent?.refreshAll()
}
