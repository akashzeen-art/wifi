package com.wifiextender.utils

object DeviceNameResolver {

    private val GENERIC_NAME = Regex(
        """^(device\s*)?(\d{1,3}\.){3}\d{1,3}$|^[0-9a-f:]{17}$|^(unknown|android|localhost)$""",
        RegexOption.IGNORE_CASE
    )

    /** Common OUI prefixes → manufacturer (phones, laptops, routers) */
    private val OUI_VENDORS = mapOf(
        "00:03:93" to "Apple", "00:05:02" to "Apple", "00:0A:27" to "Apple",
        "00:0D:93" to "Apple", "00:10:FA" to "Apple", "00:11:24" to "Apple",
        "00:14:51" to "Apple", "00:16:CB" to "Apple", "00:17:F2" to "Apple",
        "00:19:E3" to "Apple", "00:1B:63" to "Apple", "00:1C:B3" to "Apple",
        "00:1D:4F" to "Apple", "00:1E:52" to "Apple", "00:1E:C2" to "Apple",
        "00:1F:5B" to "Apple", "00:1F:F3" to "Apple", "00:21:E9" to "Apple",
        "00:22:41" to "Apple", "00:23:12" to "Apple", "00:23:32" to "Apple",
        "00:23:6C" to "Apple", "00:23:DF" to "Apple", "00:24:36" to "Apple",
        "00:25:00" to "Apple", "00:25:4B" to "Apple", "00:25:BC" to "Apple",
        "00:26:08" to "Apple", "00:26:4A" to "Apple", "00:26:B0" to "Apple",
        "00:26:BB" to "Apple", "00:30:65" to "Apple", "04:0C:CE" to "Apple",
        "04:15:52" to "Apple", "04:1E:64" to "Apple", "04:26:65" to "Apple",
        "04:48:9A" to "Apple", "04:52:C7" to "Apple", "04:54:53" to "Apple",
        "04:D3:CF" to "Apple", "04:DB:56" to "Apple", "04:E5:36" to "Apple",
        "04:F1:3E" to "Apple", "04:F7:E4" to "Apple", "08:00:07" to "Apple",
        "08:66:98" to "Apple", "08:6D:41" to "Apple", "08:70:45" to "Apple",
        "0C:30:21" to "Apple", "0C:4D:E9" to "Apple", "0C:74:C2" to "Apple",
        "10:1C:0C" to "Apple", "10:93:E9" to "Apple", "10:9A:DD" to "Apple",
        "10:DD:B1" to "Apple", "14:10:9F" to "Apple", "14:5A:05" to "Apple",
        "14:7D:DA" to "Apple", "14:8F:C6" to "Apple", "14:99:E2" to "Apple",
        "18:20:32" to "Apple", "18:34:51" to "Apple", "18:65:90" to "Apple",
        "18:9E:FC" to "Apple", "18:AF:61" to "Apple", "18:E7:F4" to "Apple",
        "1C:1A:C0" to "Apple", "1C:36:BB" to "Apple", "1C:AB:A7" to "Apple",
        "20:78:F0" to "Apple", "20:A2:E4" to "Apple", "20:C9:D0" to "Apple",
        "24:1E:EB" to "Apple", "24:A0:74" to "Apple", "24:AB:81" to "Apple",
        "24:E3:14" to "Apple", "28:0B:5C" to "Apple", "28:37:37" to "Apple",
        "28:6A:B8" to "Apple", "28:6C:07" to "Apple", "28:CF:DA" to "Apple",
        "28:CF:E9" to "Apple", "28:E0:2C" to "Apple", "28:E7:CF" to "Apple",
        "2C:BE:08" to "Apple", "2C:F0:A2" to "Apple", "30:10:E4" to "Apple",
        "30:63:6B" to "Apple", "30:90:AB" to "Apple", "34:08:BC" to "Apple",
        "34:12:98" to "Apple", "34:15:9E" to "Apple", "34:36:3B" to "Apple",
        "34:A3:95" to "Apple", "34:C0:59" to "Apple", "34:E2:FD" to "Apple",
        "38:0F:4A" to "Apple", "38:48:4C" to "Apple", "38:89:DC" to "Apple",
        "38:B5:4D" to "Apple", "38:C9:86" to "Apple", "3C:07:54" to "Apple",
        "3C:15:C2" to "Apple", "3C:2E:F9" to "Apple", "3C:AB:8E" to "Apple",
        "40:30:04" to "Apple", "40:33:1A" to "Apple", "40:6C:8F" to "Apple",
        "40:A6:D9" to "Apple", "40:B3:95" to "Apple", "40:CB:C0" to "Apple",
        "44:4C:0C" to "Apple", "48:A9:1C" to "Apple", "4C:32:75" to "Apple",
        "4C:57:CA" to "Apple", "4C:74:BF" to "Apple", "50:32:37" to "Apple",
        "50:7A:55" to "Apple", "50:EA:D6" to "Apple", "54:26:96" to "Apple",
        "54:72:4F" to "Apple", "54:9F:13" to "Apple", "58:1F:AA" to "Apple",
        "5C:59:48" to "Apple", "5C:95:AE" to "Apple", "5C:F9:38" to "Apple",
        "60:03:08" to "Apple", "60:69:44" to "Apple", "60:C5:47" to "Apple",
        "60:D9:C7" to "Apple", "60:FA:CD" to "Apple", "64:20:0C" to "Apple",
        "64:A3:CB" to "Apple", "64:B9:E8" to "Apple", "68:09:27" to "Apple",
        "68:64:4B" to "Apple", "68:96:7B" to "Apple", "68:A8:6D" to "Apple",
        "68:AE:20" to "Apple", "6C:40:08" to "Apple", "6C:72:E7" to "Apple",
        "6C:94:66" to "Apple", "70:11:24" to "Apple", "70:3E:AC" to "Apple",
        "70:56:81" to "Apple", "70:70:0D" to "Apple", "70:CD:60" to "Apple",
        "74:1B:B2" to "Apple", "74:8D:08" to "Apple", "74:E2:F5" to "Apple",
        "78:31:C1" to "Apple", "78:4F:43" to "Apple", "78:6C:1C" to "Apple",
        "78:A3:E4" to "Apple", "78:CA:39" to "Apple", "7C:01:91" to "Apple",
        "7C:11:BE" to "Apple", "7C:6D:62" to "Apple", "7C:D1:C3" to "Apple",
        "80:BE:05" to "Apple", "80:E6:50" to "Apple", "84:38:35" to "Apple",
        "84:85:06" to "Apple", "84:FC:FE" to "Apple", "88:1F:A1" to "Apple",
        "88:63:DF" to "Apple", "88:C9:D0" to "Apple", "8C:00:6D" to "Apple",
        "8C:2D:AA" to "Apple", "8C:58:77" to "Apple", "8C:85:90" to "Apple",
        "90:27:E4" to "Apple", "90:60:F1" to "Apple", "90:84:0D" to "Apple",
        "90:B0:ED" to "Apple", "90:B9:31" to "Apple", "94:E9:6A" to "Apple",
        "98:01:A7" to "Apple", "98:10:E8" to "Apple", "98:5A:EB" to "Apple",
        "98:B8:E3" to "Apple", "98:D6:BB" to "Apple", "98:E0:D9" to "Apple",
        "9C:04:EB" to "Apple", "9C:20:7B" to "Apple", "9C:F3:87" to "Apple",
        "A0:99:9B" to "Apple", "A4:5E:60" to "Apple", "A4:B1:97" to "Apple",
        "A4:C3:61" to "Apple", "A4:D1:8C" to "Apple", "A4:D1:D2" to "Apple",
        "A8:20:66" to "Apple", "A8:5B:78" to "Apple", "A8:66:7F" to "Apple",
        "A8:88:08" to "Apple", "A8:96:8A" to "Apple", "A8:BE:27" to "Apple",
        "AC:1F:74" to "Apple", "AC:29:3A" to "Apple", "AC:3C:0B" to "Apple",
        "AC:BC:32" to "Apple", "B0:34:95" to "Apple", "B0:48:1A" to "Apple",
        "B0:65:BD" to "Apple", "B4:F0:AB" to "Apple", "B8:09:8A" to "Apple",
        "B8:17:C2" to "Apple", "B8:53:AC" to "Apple", "B8:C7:5D" to "Apple",
        "BC:3A:EA" to "Apple", "BC:52:B7" to "Apple", "BC:92:6B" to "Apple",
        "C0:63:94" to "Apple", "C0:CE:CD" to "Apple", "C4:2C:03" to "Apple",
        "C8:1E:E7" to "Apple", "C8:2A:14" to "Apple", "C8:33:4B" to "Apple",
        "C8:69:CD" to "Apple", "C8:6F:1D" to "Apple", "C8:B5:B7" to "Apple",
        "CC:08:E0" to "Apple", "CC:25:EF" to "Apple", "CC:29:F5" to "Apple",
        "D0:03:4B" to "Apple", "D0:23:DB" to "Apple", "D0:A6:37" to "Apple",
        "D4:61:9D" to "Apple", "D8:1D:72" to "Apple", "D8:30:62" to "Apple",
        "D8:A2:5E" to "Apple", "D8:BB:2C" to "Apple", "DC:2B:61" to "Apple",
        "DC:37:14" to "Apple", "DC:86:D8" to "Apple", "DC:9B:9C" to "Apple",
        "E0:AC:CB" to "Apple", "E0:B5:2D" to "Apple", "E0:C9:7A" to "Apple",
        "E4:25:E7" to "Apple", "E4:8B:7F" to "Apple", "E4:C6:3D" to "Apple",
        "E8:06:88" to "Apple", "E8:80:2E" to "Apple", "EC:35:86" to "Apple",
        "F0:18:98" to "Apple", "F0:99:BF" to "Apple", "F0:B4:79" to "Apple",
        "F0:C1:F1" to "Apple", "F0:D1:A9" to "Apple", "F4:0F:24" to "Apple",
        "F4:37:B7" to "Apple", "F4:F1:5A" to "Apple", "F4:F9:51" to "Apple",
        "F8:1E:DF" to "Apple", "F8:27:93" to "Apple", "FC:25:3F" to "Apple",
        "FC:D8:48" to "Apple", "FC:E9:98" to "Apple",
        "00:12:FB" to "Samsung", "00:15:99" to "Samsung", "00:16:32" to "Samsung",
        "00:16:6B" to "Samsung", "00:16:6C" to "Samsung", "00:17:C9" to "Samsung",
        "00:17:D5" to "Samsung", "00:18:AF" to "Samsung", "00:1A:8A" to "Samsung",
        "00:1B:98" to "Samsung", "00:1C:43" to "Samsung", "00:1D:25" to "Samsung",
        "00:1E:7D" to "Samsung", "00:1F:CC" to "Samsung", "00:21:19" to "Samsung",
        "00:23:39" to "Samsung", "00:23:3A" to "Samsung", "00:23:99" to "Samsung",
        "00:23:D6" to "Samsung", "00:23:D7" to "Samsung", "00:24:54" to "Samsung",
        "00:24:90" to "Samsung", "00:24:91" to "Samsung", "00:25:66" to "Samsung",
        "00:26:37" to "Samsung", "00:26:5D" to "Samsung", "00:26:5F" to "Samsung",
        "34:AA:99" to "Samsung", "38:01:95" to "Samsung", "38:AA:3C" to "Samsung",
        "50:01:BB" to "Samsung", "50:32:75" to "Samsung", "50:56:BF" to "Samsung",
        "5C:0A:5B" to "Samsung", "5C:51:88" to "Samsung", "64:B3:10" to "Samsung",
        "68:EB:C5" to "Samsung", "6C:2F:2C" to "Samsung", "78:47:1D" to "Samsung",
        "84:25:DB" to "Samsung", "88:32:9B" to "Samsung", "8C:77:12" to "Samsung",
        "94:35:0A" to "Samsung", "A0:07:98" to "Samsung", "A0:82:1F" to "Samsung",
        "B4:79:A7" to "Samsung", "BC:20:A4" to "Samsung", "C0:BD:D1" to "Samsung",
        "CC:07:AB" to "Samsung", "D0:66:7B" to "Samsung", "D0:FC:CC" to "Samsung",
        "E4:7C:A6" to "Samsung", "E4:E0:C5" to "Samsung", "F8:04:2E" to "Samsung",
        "00:9E:C8" to "Xiaomi", "04:CF:8C" to "Xiaomi", "10:2A:B3" to "Xiaomi",
        "14:F6:5A" to "Xiaomi", "18:59:36" to "Xiaomi", "28:6C:07" to "Xiaomi",
        "34:CE:00" to "Xiaomi", "38:A4:ED" to "Xiaomi", "3C:BD:D8" to "Xiaomi",
        "50:64:2B" to "Xiaomi", "58:44:98" to "Xiaomi", "64:09:80" to "Xiaomi",
        "64:B4:73" to "Xiaomi", "74:23:44" to "Xiaomi", "78:11:DC" to "Xiaomi",
        "7C:1D:D9" to "Xiaomi", "8C:DE:52" to "Xiaomi", "98:FA:E3" to "Xiaomi",
        "9C:99:A0" to "Xiaomi", "A4:50:46" to "Xiaomi", "AC:C1:EE" to "Xiaomi",
        "B0:E2:E5" to "Xiaomi", "C4:6A:B7" to "Xiaomi", "CC:9E:A2" to "Xiaomi",
        "D4:97:0B" to "Xiaomi", "E4:46:DA" to "Xiaomi", "F0:B4:29" to "Xiaomi",
        "F8:A4:5F" to "Xiaomi", "FC:64:BA" to "Xiaomi",
        "00:E0:FC" to "Huawei", "04:C0:6F" to "Huawei", "10:1B:54" to "Huawei",
        "14:30:04" to "Huawei", "28:6E:D4" to "Huawei", "34:6A:C2" to "Huawei",
        "48:00:31" to "Huawei", "4C:54:99" to "Huawei", "50:9E:A7" to "Huawei",
        "58:2A:F7" to "Huawei", "5C:4C:A9" to "Huawei", "64:16:66" to "Huawei",
        "70:72:3C" to "Huawei", "78:D7:52" to "Huawei", "88:53:D4" to "Huawei",
        "AC:E2:D3" to "Huawei", "B4:CD:27" to "Huawei", "C8:14:79" to "Huawei",
        "D0:7A:B5" to "Huawei", "E0:24:81" to "Huawei", "F4:4E:FD" to "Huawei",
        "00:1A:11" to "Google", "00:24:E4" to "Google", "08:71:90" to "Google",
        "14:C1:4E" to "Google", "20:DF:B9" to "Google", "3C:5A:B4" to "Google",
        "54:60:09" to "Google", "58:CB:52" to "Google", "6C:AD:F8" to "Google",
        "94:EB:2C" to "Google", "A4:77:33" to "Google", "F4:F5:D8" to "Google",
        "F4:F5:E8" to "Google", "F8:8F:CA" to "Google",
        "00:1B:44" to "Dell", "00:21:70" to "Dell", "00:26:B9" to "Dell",
        "18:03:73" to "Dell", "34:E6:D7" to "Dell", "B8:CA:3A" to "Dell",
        "00:1F:3B" to "HP", "00:25:B3" to "HP", "10:60:4B" to "HP",
        "38:63:BB" to "HP", "5C:B9:01" to "HP", "94:57:A5" to "HP",
        "00:50:56" to "VMware", "00:0C:29" to "VMware", "00:15:5D" to "Microsoft",
        "00:1D:D8" to "Microsoft", "28:18:78" to "Microsoft", "7C:ED:8D" to "Microsoft",
        "00:17:88" to "Philips", "00:1E:06" to "OnePlus", "94:65:2D" to "OnePlus",
        "AC:1F:6B" to "Supermicro", "B4:2E:99" to "OnePlus", "C0:EE:FB" to "OnePlus",
        "00:0E:C6" to "ASUS", "04:D4:C4" to "ASUS", "10:BF:48" to "ASUS",
        "1C:B7:2C" to "ASUS", "2C:4D:54" to "ASUS", "60:45:CB" to "ASUS",
        "00:04:20" to "Lenovo", "00:21:86" to "Lenovo", "54:EE:75" to "Lenovo",
        "AC:38:70" to "Lenovo", "E4:B3:18" to "Lenovo", "F0:DE:F1" to "Lenovo"
    )

    fun lookupVendor(mac: String?): String? {
        if (mac.isNullOrBlank() || mac.length < 8) return null
        val oui = mac.uppercase().replace('-', ':').take(8)
        return OUI_VENDORS[oui]
    }

    fun isRealHostname(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (trimmed.length < 2) return false
        return !GENERIC_NAME.matches(trimmed)
    }

    fun nameQuality(name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val n = name.trim()
        return when {
            isRealHostname(n) && !n.startsWith("Device ", ignoreCase = true) -> 100
            n.contains(" · ") -> 60
            lookupVendor(n) != null -> 50
            n.startsWith("Device ", ignoreCase = true) -> 30
            GENERIC_NAME.matches(n) -> 10
            else -> 40
        }
    }

    fun resolveDisplayName(
        mac: String?,
        ip: String?,
        hostname: String?,
        cachedName: String? = null,
        vendor: String? = null
    ): String {
        val resolvedVendor = vendor ?: lookupVendor(mac)

        if (isRealHostname(cachedName)) return cachedName!!.trim()
        if (isRealHostname(hostname)) return hostname!!.trim()

        val ipLabel = ip?.takeIf { it.isNotBlank() }
        val macSuffix = mac?.replace(":", "")?.takeLast(4)?.uppercase()

        return when {
            resolvedVendor != null && ipLabel != null ->
                "$resolvedVendor Device · $ipLabel"
            resolvedVendor != null && macSuffix != null ->
                "$resolvedVendor · …$macSuffix"
            resolvedVendor != null ->
                "$resolvedVendor Device"
            ipLabel != null ->
                "Device · $ipLabel"
            macSuffix != null ->
                "Device · …$macSuffix"
            !mac.isNullOrBlank() ->
                "Device ${mac.takeLast(8)}"
            else ->
                "Unknown Device"
        }
    }

    fun formatDeviceLabel(deviceName: String?, vendor: String?, ip: String?, mac: String): String {
        val name = deviceName?.trim().orEmpty()
        if (isRealHostname(name) && !name.startsWith("Device ·")) return name
        return resolveDisplayName(mac, ip, name, vendor = vendor ?: lookupVendor(mac))
    }

    /** Parse hostname + MAC + IP from dumpsys tethering / connectivity output */
    fun parseDumpsysClients(dump: String): Map<String, Triple<String?, String?, String?>> {
        val result = mutableMapOf<String, Triple<String?, String?, String?>>()
        val macRegex = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")
        val ipRegex = Regex("""\b(\d{1,3}\.){3}\d{1,3}\b""")
        val hostnameRegex = Regex(
            """(?i)(?:hostname|hostName|name)[=:\s]+"?([A-Za-z0-9][A-Za-z0-9._-]{1,48})"?"""
        )

        dump.lineSequence().forEach { line ->
            val mac = macRegex.find(line)?.value?.uppercase()?.replace('-', ':') ?: return@forEach
            val ip = ipRegex.find(line)?.value
            val hostname = hostnameRegex.find(line)?.groupValues?.getOrNull(1)
                ?.takeIf { isRealHostname(it) }
            val existing = result[mac]
            result[mac] = Triple(
                hostname ?: existing?.first,
                ip ?: existing?.second,
                mac
            )
        }

        // Multi-line blocks: MAC on one line, hostname on next
        val lines = dump.lines()
        for (i in lines.indices) {
            val mac = macRegex.find(lines[i])?.value?.uppercase()?.replace('-', ':') ?: continue
            val ip = ipRegex.find(lines[i])?.value
            var hostname: String? = hostnameRegex.find(lines[i])?.groupValues?.getOrNull(1)
            if (!isRealHostname(hostname) && i + 1 < lines.size) {
                hostname = hostnameRegex.find(lines[i + 1])?.groupValues?.getOrNull(1)
            }
            val existing = result[mac]
            result[mac] = Triple(
                hostname?.takeIf { isRealHostname(it) } ?: existing?.first,
                ip ?: existing?.second,
                mac
            )
        }
        return result
    }

    /** dnsmasq lease: expiry mac ip hostname clientid */
    fun parseDhcpLeases(content: String): Map<String, Pair<String?, String?>> {
        val result = mutableMapOf<String, Pair<String?, String?>>()
        content.lineSequence().forEach { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 4) {
                val mac = parts[1].uppercase().replace('-', ':')
                val ip = parts[2]
                val hostname = parts[3].takeIf { it != "*" && isRealHostname(it) }
                result[mac] = hostname to ip
            }
        }
        return result
    }

    /** MIUI / Android tethering dump: client: /10.142.108.136 (0a:07:fa:05:74:04) */
    fun parseTetheringClientInformation(dump: String): List<com.wifiextender.utils.ConnectedClient> {
        val results = mutableListOf<com.wifiextender.utils.ConnectedClient>()
        val seen = mutableSetOf<String>()

        val clientRegex = Regex(
            """client:\s*/(\d{1,3}(?:\.\d{1,3}){3})\s*\(([0-9a-f]{2}(?::[0-9a-f]{2}){5})\)""",
            RegexOption.IGNORE_CASE
        )
        clientRegex.findAll(dump).forEach { match ->
            val ip = match.groupValues[1]
            val mac = match.groupValues[2].uppercase()
            if (mac in seen || mac == "00:00:00:00:00:00") return@forEach
            seen.add(mac)
            results.add(
                com.wifiextender.utils.ConnectedClient(
                    name = "",
                    macAddress = mac,
                    ipAddress = ip,
                    vendor = lookupVendor(mac)
                )
            )
        }

        return results
    }

    /** Parse active hotspot client IPs from tethering BPF / conntrack log lines in dumpsys. */
    fun parseTetheringBpfHotspotIps(dump: String): List<com.wifiextender.utils.ConnectedClient> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<com.wifiextender.utils.ConnectedClient>()
        val regex = Regex("""src4:\s*/(\d{1,3}(?:\.\d{1,3}){3})""")
        regex.findAll(dump).forEach { match ->
            val ip = match.groupValues[1]
            if (!isHotspotClientIp(ip) || ip in seen) return@forEach
            seen.add(ip)
            results.add(
                com.wifiextender.utils.ConnectedClient(
                    name = "",
                    macAddress = null,
                    ipAddress = ip,
                    vendor = null
                )
            )
        }
        return results
    }

    /** Stable locally-administered MAC when only IP is known (MIUI blocks ARP). */
    fun pseudoMacFromIp(ip: String): String {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return "02:00:00:00:00:01"
        return String.format("02:%02X:%02X:%02X:%02X", parts[0], parts[1], parts[2], parts[3])
    }

    /** Soft-AP interface (wlan1, ap0) — not wlan0 STA WiFi. */
    fun isHotspotApInterface(interfaceName: String): Boolean {
        val n = interfaceName.lowercase()
        return when {
            n == "wlan0" -> false
            n.matches(Regex("wlan[1-9].*")) -> true
            n.contains("ap") || n.contains("softap") || n.contains("soft_ap") -> true
            else -> false
        }
    }

    fun isHotspotClientIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        val prefixes = listOf(
            "10.142.", "192.168.43.", "192.168.49.", "192.168.137.", "192.168.176.",
            "10.0.0.", "10.42.0.", "172.20.10."
        )
        return prefixes.any { ip.startsWith(it) }
    }

    /** Parse connected clients from `dumpsys wifi` (MIUI / Android SoftAp) */
    fun parseDumpsysWifiClients(dump: String): List<com.wifiextender.utils.ConnectedClient> {
        val results = mutableListOf<com.wifiextender.utils.ConnectedClient>()
        val seen = mutableSetOf<String>()
        val macRegex = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")
        val ipRegex = Regex("""\b(10\.142\.\d{1,3}\.\d{1,3}|192\.168\.(?:43|49|137)\.\d{1,3}|10\.(?:0\.0|42\.0)\.\d{1,3}\.\d{1,3})\b""")

        dump.lineSequence().forEach { line ->
            if (!line.contains("client", ignoreCase = true) &&
                !line.contains("MAC", ignoreCase = true) &&
                !line.contains("Station", ignoreCase = true) &&
                !line.contains("SoftAp", ignoreCase = true)) return@forEach

            val mac = macRegex.find(line)?.value?.uppercase()?.replace('-', ':') ?: return@forEach
            if (mac == "00:00:00:00:00:00" || mac in seen) return@forEach
            seen.add(mac)

            val ip = ipRegex.find(line)?.value
            val hostname = Regex("""(?i)(?:hostname|hostName|name)[=:\s]+"?([A-Za-z0-9][A-Za-z0-9._-]{1,48})"?""")
                .find(line)?.groupValues?.getOrNull(1)
                ?.takeIf { isRealHostname(it) }

            results.add(
                com.wifiextender.utils.ConnectedClient(
                    name = hostname ?: "",
                    macAddress = mac,
                    ipAddress = ip,
                    vendor = lookupVendor(mac)
                )
            )
        }

        // Fallback: any hotspot-range IP + MAC pairs anywhere in dump
        if (results.isEmpty()) {
            parseDumpsysClients(dump).forEach { (mac, triple) ->
                if (mac !in seen) {
                    seen.add(mac)
                    results.add(
                        com.wifiextender.utils.ConnectedClient(
                            name = triple.first ?: "",
                            macAddress = mac,
                            ipAddress = triple.second,
                            vendor = lookupVendor(mac)
                        )
                    )
                }
            }
        }
        return results
    }
}
