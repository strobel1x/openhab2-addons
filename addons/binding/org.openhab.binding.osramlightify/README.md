# OSRAM Lightify Binding

This binding supports the OSRAM Lightify gateway and devices paired with it.

OSRAM products may also be badged as Sylvania or Ledvance. Lightify products may be labelled as SMART+ and advertised as working with Lightify (and other ZigBee compatible systems). For the sake of simplicity we refer only to OSRAM and Lightify here.

The binding uses the (undocumented but partially reverse engineered) OSRAM binary protocol for communication with the gateway. It does NOT use the cloud hosted REST API. This is not to say that the gateway will not be reporting home to the OSRAM servers (or more specifically their partner's, arrayent.com) if it is able. It is up to you to either not provide it with  a default gateway or to firewall it at the egress to the Internet if you wish to be certain of your privacy. However note that some operations such as pairing and firmware updates MUST be performed using the Lightify app and this may require Internet access either for the app or the gateway.


## Disclaimer

This binding has been implemented using publically available information available on the Internet, most of which has been derived from careful analysis of packet captures. The protocol is not currently documented by OSRAM GmbH and it is thus not possible to make any guarantees of functionality or fitness for purpose.

Use of this binding **might brick your device**, **render it non-functional**, **change behavior of any connected device** and/or **may void your warranty**. Any usage is **strictly at your own risk**. The author(s) **are not liable** for any losses and damages that may arise.

The author(s) are **not affiliated** to OSRAM GmbH in any way. Furthermore this binding is **not officially published by or approved by** by OSRAM GmbH or any of its subsidiaries or affiliates.


## Credits

* Mike Jagdis (@mjagdis) - this binding
* Christoph Engelbert (@noctarius2k) - much of the initial protocol analysis and an earlier binding


## Supported Things

This binding supports OSRAM Lightify gateways along with lights, power switches and motion sensors that are paired with them. Currently wireless switches are **not** supported.

Note that as of gateway firmware 1.1.3.53 and motion sensor firmware 1E005310 the temperature feature of the OSRAM motion sensor is **not** supported, it is not possible to enable/disable the motion sensor via openHAB and that the triggered status of a motionsensor thing indicates whether an associated action has been triggered rather than simply whether motion has been detected. You **must** associate an action with the motion sensor via the Lightify app or the triggered status never changes. It is not, however, necessary for the associated device to be powered on or reachable for the action to be triggered. The triggered status in openHAB therefore indicates when motion has been detected, the time is within the action's time window and the time since motion was last detected is less than the action's time to off.


### Tested Devices

* CLA 60 RGBW - 10W, 810lm, Ra > 85, quoted white range of 2000K - 6500K
* PAR16 50 RGBW - 6W, 300lm, Ra 80, quoted white range of 2000K - 6500K
* OSRAM SMART+ Motion Sensor

Both lights show a probed white range of 1801K - 6622K with firmware 01020412 and 1501K - 8000K with firmware 01020510. It isn't clear whether we see the actual ZigBee limits or some gateway concept of what lights should be capable of. No attempt has been made to verify the range via external measurement. However the increased range with the newer firmware is very real. The lower end of the range could now be described as golden firelight!

(This extended range looks as though it may be using the RGB LEDs so may not be available with non-RGBW lights)

The PAR16s exhibit somewhat choppy colour transitions and, in some cases, significant steps. This is probably due to having fewer LEDs internally and thus struggling to handle mixes with lower component levels relative to the other(s). To be fair the quoted Ra is 80 so no one is claiming high accuracy and it does all fit into a standard size PAR16/GU10 package. The fully saturated primary and secondary colours are satisfyingly bold, with the exception of yellow which seems pretty much unobtainable.


## Features

### High Speed

Unlike the Hue hubs the Lightify gateway does not appear to have any rate limits. It is possible to send back-to-back changes continuously as fast as the gateway (and the ZigBee network behind it) can handle.


### Gateway Group Support

If you have groups configured on your gateway these are presented as things to openHAB. OpenHAB has no knowledge of what devices are in the group however (and the devices themselves do not need to be added as things unless you want individual control as well) so a group always has all possible channels available and has a fixed white temperature range (see below) set in its properties.


### Dynamic Polling

Polling is necessary both to monitor the state of devices and to discover newly paired devices. The binding adjusts the polling interval between configurable minimum and maximum values depending on activity in order to reduce CPU/power usage while maintaining a reasonable degree of responsiveness.


### Transition Times

The OSRAM Lightify gateway allows a transition time to be specified for every change. Transition times may be configured at the bridege or device level (with the device level transition time being preferred if set). This allows you to set hallway/stair lights to come on instantly while letting room lights fade in gently. A separate time may be configured for transitions to off so that you can have quick on and soft-off at the same time.


### White Temperature Auto-ranging

Different devices can have different white temperature ranges. The range possible for each device is probed when the device is added as a thing and stored in its properties. You can override the probed range by setting the minimum and maximum values either in the device config or the bridge config. The temperature range for the device is determined from the possible minimum and maximum values using the order of preference: device config, bridge config, device probed. This allows a common range to be set for some or all devices so that temperature changes via a slider are consistent regardless of actual capabilities (although, obviously, devices will never display a temperature outside their physical capabilities).

N.B. The range advertised by devices in response to probing is neither consistent with the quoted range nor constant between firmware versions. The actual range supported by hardware is likely to be a subset of the probed range. If you want to avoid areas at the top and bottom of temperature sliders where moving the slider does not change the temperature you will have to configure the range manually. Currently no table of tested temperature capabilities for lights is available, nor is there any way to determine the specific type of a particular device.

**Important**: As of gateway/wifi firmware 1.2.2.0 we no longer see adjusted temperature values in response to our probes so the binding itself limits the range to 1501-8000K. This matches the range given by the prior firmware for RGBW lights which is itself an enhancement of the advertised range using the colour LEDs to extend the limits.


### State restore after powercycle

If a device is powercycled quickly openHAB will autmatically restore it to the last known state when it reappears. This makes things like firmware upgrades as unobtrusive as possible. If a device is off for several minutes it will remain at it's power on defaults until it is changed.


## Discovery

Discovery of OSRAM Lightify gateways and paired devices is an automatic process. Install the binding and the gateway will show up in the Inbox. If it does not (multicast may not be enabled across your APs/hubs/switches/routers) you can add the gateway manually and specify either a hostname or an IP for it. (If you use an IP you are strongly recommended to either configure your gateway with a static IP or configure your DHCP server with a fixed IP for the gateway!)

After adding gateways, they are automatically scanned for paired devices and current device states immediately and then periodically according to the polling interval set in the configuration for the thing representing the gateway.


## Notes

### Power on/off

When devices are powered on it typically takes several seconds for them to join the ZigBee network and for the gateway poll to see them and bring the things online in openHAB. If multiple devices are powered on at the same time they join the network one at a time serially so it can take quite a while until everything is done.

When devices are powered off it takes 10 minutes for them to go offline in openHAB. This is a limitation of the gateway and protocol(s) involved.

### Pairing

When pairing devices with a gateway using the Lightify app sometimes the app appears to have a name for the device but the gateway does not actually have it set and so the device shows up in the openHAB inbox with a generic name. This is not a problem with openHAB or the OSRAM Lightify binding. If this happens you can correct the gateway via the Lightify app by tapping on the device, tapping on the name at the top of the screen, then tapping done to write the name to the gateway. It does not appear to be necessary to change the name at all. OpenHAB will then update the inbox entry with the new name on the next poll (you may need to reload paperUI to see it though).


## Channels

The OSRAM Lightify supports the following channels (although not all channels are available on all devices):

| Channel            | Item Type    | Description                                           |
|--------------------|--------------|-------------------------------------------------------|
| color              | Color        | Changes the color                                     |
| switch             | Switch       | Switch the device on/off                              |
| dimmer             | Dimmer       | Set the brightness between 0% and 100%                |
| temperature        | Dimmer       | Set the white temperature between 0% and 100%         |
| absTemperature     | Number       | Set the white temperature to an absolute Kelvin value |

Note that the temperature and absTemperature channels are linked so that changing one changes the other as well.


## Full Example

Textual configuration is not supported. Lightify devices do not have any markings that would allow them to be added manually. They **MUST** be paired with a gateway and the gateway **MUST** be queried to find out how to address them. It would in theory be possible to then take this information and to create a suitable `.things` file however this has not been tested.