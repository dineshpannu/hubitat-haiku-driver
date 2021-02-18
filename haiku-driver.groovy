import groovy.transform.Field

metadata {
    definition(name: "Haiku Fan", namespace: "community", author: "Zack Brown") {
        capability "FanControl"
        capability "SwitchLevel"
        capability "Switch"
        capability "Light"
        capability "Refresh"

        command "reverseFan"
        
        attribute "fanDirection", "string"
    }
}

preferences {
    section("Device Selection") {
        input(name: "deviceName",          type: "text", title: "Device Name",                      description: "", required: true, defaultValue: "")
        input(name: "deviceIp",            type: "text", title: "Device IP Address",                description: "", required: true, defaultValue: "")
        input(name: "enableComponents",    type: "bool", title: "Enable child devices",             defaultValue: false)
        input(name: "txtEnable",           type: "bool", title: "Enable descriptionText logging",   defaultValue: true)
        input(name: "logEnable",           type: "bool", title: "Enable debug logging",             defaultValue: false)
    }
}

//
// Constants
//
// Number of light graduations Haiku supports.
@Field final int HAIKU_LIGHT_LEVELS = 16
// Ratio of light levels to percentage level. 1 Haiku light level every 6.25%
@Field final double HAIKU_LIGHT_SPREAD = (double)100/HAIKU_LIGHT_LEVELS
// Number of fan speeds Haiku supports.
@Field final int HAIKU_FAN_SPEEDS = 7
// Ratio of fan speed levels to percentage level. 1 Haiku speed level every 14.29%
@Field final double HAIKU_FAN_SPREAD = (double)100/HAIKU_FAN_SPEEDS
// Haiku Sub Devices
@Field final String SUBDEVICE_FAN = "FAN"
@Field final String SUBDEVICE_LIGHT = "LIGHT"
// Haiku Functions
@Field final String FUNCTION_POWER = "PWR"
@Field final String FUNCTION_LEVEL = "LEVEL"
@Field final String FUNCTION_SPEED = "SPD"
@Field final String FUNCTION_DIRECTION = "DIR"
// Haiku Commands
@Field final String COMMAND_ON = "ON"
@Field final String COMMAND_OFF = "OFF"
@Field final String COMMAND_FORWARD = "FWD"
@Field final String COMMAND_REVERSE = "REV"
// Child drivers
@Field final String LIGHT_COMPONENT_DRIVER = "Generic Component Dimmer"
@Field final String FAN_COMPONENT_DRIVER = "Haiku Fan Component"
@Field final String LIGHT_COMPONENT_NAMESPACE = "hubitat"
@Field final String FAN_COMPONENT_NAMESPACE = "community"
// Child names
@Field final String LIGHT_COMPONENT_NAME = "Haiku Light"
@Field final String FAN_COMPONENT_NAME = "Haiku Fan"

def lightNetworkId() {
    return "${device.id}:Light";
}
def fanNetworkId() {
    return "${device.id}:Fan";
}



def installed() {
    log.info "installed"
    device.updateSetting("txtEnable",[type:"bool",value:true])
}

def updated() {
    log.info "updated"
    log.warn "description logging is: ${txtEnable == true}, debug logging is: ${logEnable}"
       
    def childLight = getChildDevice(lightNetworkId())
    def childFan = getChildDevice(fanNetworkId())

    //
    // Enable and disable child devices
    //
    if (enableComponents) {
        if (null == childLight) {
            log.info "adding child light device"
            childLight = addChildDevice(LIGHT_COMPONENT_NAMESPACE, LIGHT_COMPONENT_DRIVER, lightNetworkId(), [name: LIGHT_COMPONENT_NAME, isComponent: true])
        }
        if (null == childFan) {
            log.info "adding child fan device"
            childFan = addChildDevice(FAN_COMPONENT_NAMESPACE, FAN_COMPONENT_DRIVER, fanNetworkId(), [name: FAN_COMPONENT_NAME, isComponent: true])
        }
    }
    else {
        if (childLight) {
            log.info "removing child light device"
            deleteChildDevice(lightNetworkId())
        }
        if (childFan) {
            log.info "removing child fan device"
            deleteChildDevice(fanNetworkId())
        }
    }

    if (deviceName && deviceIp) {
        refresh()
    }
}

def parse(String description) {
    def map = parseLanMessage(description)
    def bytes = map["payload"].decodeHex()
    def response = new String(bytes)

    if (txtEnable) {
        log.info "parse response: ${response}"
    }

    def values = response[1..-2].split(';')
    switch (values[1]) {
        case SUBDEVICE_LIGHT:
            switch (values[2]) {
                case FUNCTION_POWER:
                    refreshLightLevel()
                    String power = values[3].toLowerCase();
                    updateChildLightSwitchState(power)
                    if (shouldParentSwitchUpdateWithLight(power)) {
                        return createEvent(name: "switch", value: power)
                    }
                    break;
                case FUNCTION_LEVEL:
                    def events = [];
                    int haikuLevel = values[4].toInteger()
                    state.haikuLevel = haikuLevel

                    def power = (0 == haikuLevel) ? "off" : "on"
                    updateChildLightSwitchState(power)
                    if (shouldParentSwitchUpdateWithLight(power)) {
                        events << createEvent(name: "switch", value: power)
                    }
                    
                    int level = (int)Math.ceil(haikuLevel * HAIKU_LIGHT_SPREAD)
                    updateChildLightLevelState(level)
                    events << createEvent(name: "level", value: level)
                    return events;
            }
            break
        case SUBDEVICE_FAN:
            switch (values[2]) {
                case FUNCTION_POWER:
                    refreshFanSpeed()
                    String power = values[3].toLowerCase()
                    updateChildFanSpeedState(power)
                    updateChildFanSwitchState(power)

                    def events = [];
                    if (shouldParentSwitchUpdateWithFan(power)) {
                        events << createEvent(name: "switch", value: power)
                    }
                    events << createEvent(name: "speed", value: power)
                    return events
                case FUNCTION_SPEED:
                    int haikuSpeed = values[4].toInteger()
                    state.haikuSpeed = haikuSpeed
                    
                    String speed = translateHaikuSpeedToHubitatSpeed(haikuSpeed)
                    updateChildFanSpeedState(speed)

                    int level = translateHaikuSpeedToSwitchLevel(haikuSpeed)
                    updateChildFanLevelState(level)

                    def events = [];
                    String power = (haikuSpeed > 0) ? "on" : "off"
                    updateChildFanSwitchState(power)

                    if (shouldParentSwitchUpdateWithFan(power)) {
                        events << createEvent(name: "switch", value: power)
                    }
                    events << createEvent(name: "speed", value: speed)
                    return events
                case FUNCTION_DIRECTION:
                    refreshFanSpeed()
                    String haikuDirection = values[3]
                    String direction = (COMMAND_FORWARD == haikuDirection) ? "forward" : "reverse"
                    updateChildFanDirectionState(direction)
                    return createEvent(name: "fanDirection", value: direction)
            }
            break
    }

}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void debugLog(String message) {
    if (logEnable) {
        log.debug message
    }
}



//
// Command reverseDirection
//
def reverseFan() {
    if (device.currentValue("fanDirection") == "forward") {
        setFanDirection(COMMAND_REVERSE)
    } else {
        setFanDirection(COMMAND_FORWARD)
    }
}


// 
// Capability Refresh
//
def refresh() {
    refreshLightLevel()

    // Parsing refreshFanDirection() executes refreshFanSpeed() as well
    refreshFanDirection()
}



//
// Capability Switch
//
def on() {
    sendLightPowerCommand(COMMAND_ON)

    if (enableComponents) {
        // Haiku can't handle power on commands in quick succession
        pauseExecution(800)
        sendFanPowerCommand(COMMAND_ON)
    }
}

def off() {
    sendLightPowerCommand(COMMAND_OFF)

    if (enableComponents) {
        // Haiku can't handle power off commands in quick succession
        pauseExecution(800)
        sendFanPowerCommand(COMMAND_OFF)
    }
}

Boolean shouldParentSwitchUpdateWithLight(String power) {
    Boolean shouldUpdate = false;

    // Only update parent if child components are not enabled, or light is being turned on, or if both fan and light are off.
    // This allows switch to be on if at least one child is on
    if ((!enableComponents) || ("on" == power) || ("off" == device.currentValue("speed"))) {
        shouldUpdate = true;
    }

    return shouldUpdate
}

Boolean shouldParentSwitchUpdateWithFan(String power) {
    Boolean shouldUpdate = false;
    def childLight = getChildDevice(lightNetworkId())
    
    // Only update parent if child components are enabled and, fan is being turned on or if both fan and light are off.
    // This allows switch to be on if at least one child is on
    if ((enableComponents) && (("on" == power) || ("off" == childLight.currentValue("switch")))) {
        shouldUpdate = true
    }

    return shouldUpdate
}



//
// Capability SwitchLevel
//
def setLevel(level) {
    setLevel(level, 0)
}

def setLevel(level, duration) {
    sendLightLevelCommand(level)
}

def sendLightLevelCommand(level) {
    if (level > 100) {
        level = 100
    }
    if (level < 0) {
        level = 0
    }
    
    int haikuLevel = (int)Math.ceil(level / HAIKU_LIGHT_SPREAD)
    debugLog "level [${level}] haikuLevel [${haikuLevel}]"

    sendLightLevel(haikuLevel)
}

int translateHaikuSpeedToSwitchLevel(int haikuSpeed) {
    int level = (int)Math.ceil(haikuSpeed * HAIKU_FAN_SPREAD)
    
    return level
}



//
// Capability FanControl
//
def setSpeed(fanspeed){
    debugLog "fanspeed [${fanspeed}]"

    switch (fanspeed) {
        case "on":
            sendFanPowerCommand(COMMAND_ON)
            break
        case "off":
            sendFanPowerCommand(COMMAND_OFF)
            break
        case "low":
            sendFanSpeedCommand(1)
            break
        case "medium-low":
            sendFanSpeedCommand(2)
            break
        case "medium":
            sendFanSpeedCommand(4)
            break
        case "medium-high":
            sendFanSpeedCommand(6)
            break
        case "high":
            sendFanSpeedCommand(7)
            break
    }
}

def translateHaikuSpeedToHubitatSpeed(int haikuSpeed) {
    def hubitatSpeed = "off"

    switch (haikuSpeed) {
        case 0:
            hubitatSpeed = "off"
            break
        case 1:
            hubitatSpeed = "low"
            break
        case 2:
            hubitatSpeed = "medium-low"
            break
        case 3:
        case 4:
            hubitatSpeed = "medium"
            break
        case 5:
        case 6:
            hubitatSpeed = "medium-high"
            break
        case 7:
            hubitatSpeed = "high"
            break
    }

    return hubitatSpeed
}



//
// Haiku commands
//
def setFanDirection(String direction) {
    sendCommand(SUBDEVICE_FAN, FUNCTION_DIRECTION, "SET;${direction}")
}

def refreshFanDirection() {
    sendCommand(SUBDEVICE_FAN, FUNCTION_DIRECTION, "GET;ACTUAL")
}

def sendLightPowerCommand(String command) {
    sendCommand(SUBDEVICE_LIGHT, FUNCTION_POWER, command)
}

def refreshLightLevel() {
    sendCommand(SUBDEVICE_LIGHT, FUNCTION_LEVEL, "GET;ACTUAL")
}

def sendLightLevel(int haikuLevel) {
    sendCommand(SUBDEVICE_LIGHT, FUNCTION_LEVEL, "SET;${haikuLevel}")
}

def sendFanPowerCommand(String command) {
    sendCommand(SUBDEVICE_FAN, FUNCTION_POWER, command)
}

def refreshFanSpeed() {
    sendCommand(SUBDEVICE_FAN, FUNCTION_SPEED, "GET;ACTUAL")
}

def sendFanSpeedCommand(int level) {
    sendCommand(SUBDEVICE_FAN, FUNCTION_SPEED, "SET;${level}")
}

def sendCommand(String haikuSubDevice, String haikuFunction, String command) {
    def haikuCommand = generateCommand(haikuSubDevice, haikuFunction, command)
    sendUDPRequest(settings.deviceIp, "31415", haikuCommand)
}

static def generateCommand(haikuSubDevice, haikuFunction, command) {
    return "<ALL;${haikuSubDevice};${haikuFunction};${command}>"
}

def sendUDPRequest(address, port, payload) {
    def hubAction = new hubitat.device.HubAction(payload,
            hubitat.device.Protocol.LAN,
            [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
             destinationAddress: "${address}:${port}"])
    sendHubCommand(hubAction)
}



//
// Common child device methods
//
void componentOn(childDevice){
    debugLog "received on request from ${childDevice.displayName} [${childDevice.deviceNetworkId}] [${lightNetworkId()}]"

    if (lightNetworkId() == childDevice.deviceNetworkId) {
        sendLightPowerCommand(COMMAND_ON)
    }
    else {
        sendFanPowerCommand(COMMAND_ON)
    }
}

void componentOff(childDevice){
    debugLog "received off request from ${childDevice.displayName}"
    
    if (lightNetworkId() == childDevice.deviceNetworkId) {
        sendLightPowerCommand(COMMAND_OFF)
    }
    else {
        sendFanPowerCommand(COMMAND_OFF)
    }
}

void componentSetLevel(childDevice, level, transitionTime = null) {
    debugLog "received setLevel(${level}, ${transitionTime}) request from ${childDevice.displayName}"
    
    if (lightNetworkId() == childDevice.deviceNetworkId) {
        sendLightLevelCommand(level)
    } 
    else {
        int haikuSpeed = (int)Math.ceil(level / HAIKU_FAN_SPREAD)
        sendFanSpeedCommand(haikuSpeed)
    }
}

void componentRefresh(childDevice) {
    debugLog "received refresh request from ${childDevice.displayName}"
    
    if (lightNetworkId() == childDevice.deviceNetworkId) {
        refreshLightLevel()
    }
    else {
        // Parsing refreshFanDirection() executes refreshFanSpeed() as well
        refreshFanDirection()
    }
}



//
// Child light device methods
//
void componentStartLevelChange(childDevice, direction) {
    debugLog "received startLevelChange(${direction}) request from ${childDevice.displayName}"
    
    
    if (direction == "up") {
        levelUp() 
    }
	else {
        levelDown() 
    }
}

def levelUp() {
    int newLevel = state.haikuLevel + 1
    if (newLevel > HAIKU_LIGHT_LEVELS) { newLevel = HAIKU_LIGHT_LEVELS }
		
    componentSetLevel(getChildDevice(lightNetworkId()), newLevel)

    if (newLevel < HAIKU_LIGHT_LEVELS) {
        runIn(1, levelUp)
    }
}

def levelDown() {
    int newLevel = state.haikuLevel - 1
    if (newLevel < 0) { newLevel = 0 }
		
    componentSetLevel(getChildDevice(lightNetworkId()), newLevel)

    if (newLevel > 0) {
        runIn(1, levelDown)
    }
}

void componentStopLevelChange(childDevice) {
    debugLog "received stopLevelChange request from ${childDevice.displayName}"
    
    unschedule(levelUp)
	unschedule(levelDown)
}

void updateChildLightSwitchState(power) {
    if (enableComponents) {
        def childDevice = getChildDevice(lightNetworkId())
        childDevice.parse([[name:"switch", value:power, descriptionText:"Child ${childDevice.displayName} switch is set to ${power}"]])
    }
}

void updateChildLightLevelState(level) {
    if (enableComponents) {
        def childDevice = getChildDevice(lightNetworkId())
        childDevice.parse([[name:"level", value:level, descriptionText:"Child ${childDevice.displayName} level is set to ${level}%", unit: "%"]])

        // Set child switch state to on if level is greater than zero
        //
        if (level > 0) {
            if ("off" == childDevice.currentValue("switch")) {
                childDevice.parse([[name:"switch", value:"on", descriptionText:"Child ${childDevice.displayName} switch is set to on"]])
            }
        } else {
            if ("on" == childDevice.currentValue("switch")) {
                childDevice.parse([[name:"switch", value:"off", descriptionText:"Child ${childDevice.displayName} switch is set to off"]])
            }
        }
    }
}



//
// Child fan device methods
//
void componentCycleSpeed(childDevice) {
    debugLog "received cycleSpeed request from ${childDevice.displayName}. Current speed: [${device.currentSpeed}] Haiku speed: [${state.haikuSpeed}]"
    

    if ("off" == device.currentspeed) {
        componentOn(childDevice)
    }

    int newSpeed = 1
    if (state.haikuSpeed) {
        newSpeed = state.haikuSpeed + 1;
    }
    if (newSpeed > HAIKU_FAN_SPEEDS) {
        newSpeed = 1;
    }

    sendFanSpeedCommand(newSpeed)
}

void componentSetSpeed(childDevice, fanSpeed) {
    debugLog "received setSpeed request from ${childDevice.displayName}."
       
    setSpeed(fanSpeed)
}

void componentReverseFan(childDevice) {
    debugLog "received reverseFan request from ${childDevice.displayName}."

    reverseFan()    
}

void updateChildFanSpeedState(speed) {
    if (enableComponents) {
        def childDevice = getChildDevice(fanNetworkId())
        childDevice.parse([[name:"speed", value:speed, descriptionText:"Child ${childDevice.displayName} speed is set to ${speed}"]])
    }
}

void updateChildFanSwitchState(power) {
    if (enableComponents) {
        def childDevice = getChildDevice(fanNetworkId())
        childDevice.parse([[name:"switch", value:power, descriptionText:"Child ${childDevice.displayName} switch is set to ${power}"]])
    }
}

void updateChildFanLevelState(level) {
    if (enableComponents) {
        def childDevice = getChildDevice(fanNetworkId())
        childDevice.parse([[name:"level", value:level, descriptionText:"Child ${childDevice.displayName} level is set to ${level}"]])
    }
}

void updateChildFanDirectionState(direction) {
    if (enableComponents) {
        def childDevice = getChildDevice(fanNetworkId())
        childDevice.parse([[name:"fanDirection", value:direction, descriptionText:"Child ${childDevice.displayName} direction is set to ${direction}"]])
    }
}
