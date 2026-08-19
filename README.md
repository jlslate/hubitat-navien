# Hubitat ↔ Navien NaviLink (cloud)

A Hubitat app and drivers that talk to a Navien tankless water heater / combi boiler
through Navien's **NaviLink cloud service** — no local wiring, no serial adapter, no
extra bridge machine. The hub authenticates against the same cloud API the NaviLink
mobile app uses.

## What's here

| File | Type | Role |
| --- | --- | --- |
| [`apps/navien-navilink-cloud.groovy`](apps/navien-navilink-cloud.groovy) | App | Signs in to NaviLink, lists the account's gateways, creates a device for each, keeps the AWS credentials fresh |
| [`drivers/navien-navilink-gateway.groovy`](drivers/navien-navilink-gateway.groovy) | Driver | Owns the cloud connection for one NaviLink gateway (MQTT + AWS SigV4), routes messages |
| [`drivers/navien-navilink-water-heater.groovy`](drivers/navien-navilink-water-heater.groovy) | Driver | One heating channel: switch, temperature, setpoint, flow, gas usage, recirculation |

## Install

### With Hubitat Package Manager

Use HPM's *Install → From a URL* and paste:

```
https://raw.githubusercontent.com/jlslate/hubitat-navien/main/packageManifest.json
```

HPM installs the app and both drivers, and will offer updates as they are published.
Then skip to step 3 below.

### By hand

1. **Drivers first.** In Hubitat, go to *Developer tools → Drivers code → New driver*,
   paste `navien-navilink-gateway.groovy`, Save. Repeat for
   `navien-navilink-water-heater.groovy`.
2. **App.** *Developer tools → Apps code → New app*, paste
   `navien-navilink-cloud.groovy`, Save.
3. *Apps → Add user app → Navien NaviLink (Cloud)*.
4. Enter the email address and password you use for the NaviLink mobile app, tap
   **Discover NaviLink devices**, then **Done**.

The gateway device connects on its own and creates one water-heater device per heating
channel the unit reports. Give it a minute after install.

## What you get

On each water-heater channel device:

| Attribute | Meaning |
| --- | --- |
| `switch` | Unit power (`on` / `off`) |
| `temperature` | Current outlet (hot water) temperature |
| `heatingSetpoint` | Domestic hot water setpoint |
| `inletTemperature` / `outletTemperature` | Cold in / hot out |
| `flowRate` | Hot water flow, GPM (or LPM on a Celsius unit) |
| `gasUsage` | Instantaneous gas use, BTU/h (or kcal/h) |
| `cumulativeGasUsage` | Lifetime gas use, ft³ (or m³) |
| `onDemand` | On-demand recirculation ("hot button") state |
| `operatingState` | `heating` / `idle` / `off` |
| `errorCode` | Non-zero when the unit reports a fault |
| `minSetpoint` / `maxSetpoint` | The range the unit will accept |

Commands: `on`, `off`, `setHeatingSetpoint(temp)`, `setDHWTemperature(temp)`,
`onDemandOn`, `onDemandOff`, `refresh`.

Temperatures are reported and accepted in the **hub's** temperature scale; the driver
converts to whatever the unit was commissioned in.

## How the cloud connection works

Navien's cloud is two separate things, and the split is why this is an app *plus* a
driver:

1. **REST** at `https://nlus.naviensmartcontrol.com/api/v2` — `POST /user/sign-in`
   returns an access token *and a set of temporary AWS IAM credentials*;
   `POST /device/list` enumerates the gateways. That's all the REST API does.
2. **AWS IoT Core** — every piece of live status and every control command travels over
   MQTT, on topics keyed by the gateway MAC, your user id, and the MQTT client id.

Hubitat only allows an MQTT client inside a driver, so the app handles (1) and hands the
credentials to the gateway driver, which handles (2).

AWS IoT does not accept a password over WebSockets; it wants a SigV4 pre-signed URL.
The gateway driver builds that URL itself — HMAC-SHA256 signing chain, canonical
request, the whole thing — in `presignIotWebsocketUrl()`. The output was verified to be
byte-identical to what the AWS IoT Device SDK produces for the same inputs.

Polling: the driver holds the MQTT session open and publishes a `channelstatus` request
on the interval set in the app (default 5 minutes). The unit also pushes status frames
when something changes. Credentials are re-issued every 30 minutes, and the connection
is rebuilt with the fresh set.

### If the connection drops to HTTPS-only

Hubitat's documented MQTT transports are `tcp://` and `ssl://`, but a `wss://` broker URL
works — this integration runs over one. Two details make it work, and both are easy to
get wrong if you adapt this code:

- AWS IoT authenticates a WebSocket connection with a SigV4 pre-signed URL, built in
  `presignIotWebsocketUrl()`.
- The canonical request must sign the host **including the port** (`<endpoint>:443`),
  because the underlying client writes `Host: <endpoint>:443` on the upgrade and AWS
  recomputes the signature over the header it received. Signing a bare host earns a
  silent 403 on the upgrade, which surfaces as a detail-free `MqttException`.

If MQTT cannot be established after four attempts, the gateway's `connection` attribute
goes to **`http-fallback`** and commands route to the AWS IoT HTTPS publish endpoint
(port 8443) instead. On/off, setpoint and recirculation still work in that mode, but no
status can be received, because an HTTPS publish has no subscribe half. It keeps
retrying MQTT every 15 minutes, alternating how it signs the host in case a different
platform build sends a bare one.

So: check the gateway device's `connection` attribute. `connected` is the normal state.

## Notes and limits

- Cloud-dependent by design. If Navien's service or your internet is down, so is this.
- Your NaviLink password is stored in the app's settings, as with any cloud integration.
- The protocol is not published by Navien. Field encodings here follow the
  community-reverse-engineered NaviLink protocol, cross-checked against the Home
  Assistant integrations linked below.
- Verified end to end on a Hubitat hub against a real NaviLink account: sign-in, device
  discovery, MQTT over WebSockets, and live channel status.
- Built for the v2 API shape used by NaviLink and NaviLink Lite gateways. Heat-pump
  units (NWP500) use a newer message set and are not covered.
- Multi-channel and cascaded (multi-unit) systems are handled: one Hubitat device per
  channel, per-unit temperatures averaged, flow and gas summed.

## Credit

Protocol details were derived from the community work in
[nikshriv/navilink_api](https://github.com/nikshriv/navilink_api),
[nikshriv/hass_navien_water_heater](https://github.com/nikshriv/hass_navien_water_heater),
and [rudybrian/PyNavienSmartControl](https://github.com/rudybrian/PyNavienSmartControl).

Copyright (c) 2026 jlslate. All rights reserved. No license is granted for reuse
or redistribution.
