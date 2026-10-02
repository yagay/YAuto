package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class AndroidNetworkUtilityFeaturePack : FeaturePack {
    override val id: String = "android.network.utility"

    override fun install(registry: FeatureRegistry) {
        registerDnsResolve(registry)
        registerLocalAddresses(registry)
        registerUdpSend(registry)
        registerLocalAddressMatch(registry, FeatureKind.STATE, "android.state.local_address_match")
        registerLocalAddressMatch(registry, FeatureKind.CONDITION, "android.condition.local_address_match")
        registerTcpReachable(registry, FeatureKind.STATE, "android.state.tcp_reachable")
        registerTcpReachable(registry, FeatureKind.CONDITION, "android.condition.tcp_reachable")
        registerWaitForTcp(registry)
    }

    private fun registerDnsResolve(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.network.dns.resolve"), FeatureKind.ACTION,
                "Resolve DNS hostname", "Resolve a hostname and store all returned IP addresses in a list variable",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("host", "Hostname", true),
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store addresses in variable", true),
                ),
                keywords = setOf("dns", "resolve", "hostname", "ip address"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val host = feature.config.string("host").resolveVariables(ctx.variables).trim()
            if (!isReasonableHost(host)) return@registerAction ActionExecutionResult(false, message = userText("feature.network_host_invalid"))
            val timeout = feature.config.long("timeoutMs", 10_000).coerceIn(250, 30_000)
            val addresses = withTimeoutOrNull(timeout) {
                withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct() }.getOrNull()
                }
            } ?: return@registerAction ActionExecutionResult(false, message = userText("feature.network_dns_failed", host))
            if (addresses.isEmpty()) return@registerAction ActionExecutionResult(false, message = userText("feature.network_dns_failed", host))
            val output = ConfigValue.ListValue(addresses.map(ConfigValue::StringValue))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerLocalAddresses(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.network.local_addresses"), FeatureKind.ACTION,
                "Get local network addresses", "List active network-interface addresses and store structured results in a variable",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Toggle("includeLoopback", "Include loopback"),
                    FieldSchema.Toggle("includeIpv6", "Include IPv6"),
                    FieldSchema.Variable("resultVariable", "Store address list in variable", true),
                ),
                keywords = setOf("ip", "network interface", "local address", "ipv4", "ipv6"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val values = withContext(Dispatchers.IO) {
                collectLocalAddresses(
                    includeLoopback = feature.config.boolean("includeLoopback"),
                    includeIpv6 = feature.config.boolean("includeIpv6", true),
                )
            }
            val output = ConfigValue.ListValue(values.map { item ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "interface" to ConfigValue.StringValue(item.interfaceName),
                        "address" to ConfigValue.StringValue(item.address),
                        "ipv6" to ConfigValue.BooleanValue(item.ipv6),
                        "loopback" to ConfigValue.BooleanValue(item.loopback),
                    )
                )
            })
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerUdpSend(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.network.udp.send"), FeatureKind.ACTION,
                "Send UDP datagram", "Send UTF-8 text to a UDP host and port, including broadcast destinations when enabled",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("host", "Host", true),
                    FieldSchema.Number("port", "Port", true, min = 1.0, max = 65535.0),
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Toggle("broadcast", "Allow broadcast"),
                ),
                keywords = setOf("udp", "datagram", "socket", "broadcast", "network"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val host = feature.config.string("host").resolveVariables(ctx.variables).trim()
            val port = feature.config["port"].numberOrNull()?.toInt()
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            if (!isReasonableHost(host) || port == null || !isValidPort(port)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.network_host_port_invalid"))
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > 65_507) return@registerAction ActionExecutionResult(false, message = userText("feature.network_udp_payload_too_large"))
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    val address = InetAddress.getByName(host)
                    DatagramSocket().use { socket ->
                        socket.broadcast = feature.config.boolean("broadcast")
                        socket.send(DatagramPacket(bytes, bytes.size, address, port))
                    }
                    true
                }.getOrDefault(false)
            }
            ActionExecutionResult(sent, ConfigValue.NumberValue(bytes.size.toDouble()), if (sent) null else userText("feature.network_udp_send_failed", host, port))
        }
    }

    private fun registerLocalAddressMatch(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Local IP address matches", "Check active local network interfaces for an address and interface-name match",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Text("addressContains", "Address contains"),
                FieldSchema.Text("interfaceContains", "Interface contains"),
                FieldSchema.Choice("family", "Address family", true, listOf("any", "ipv4", "ipv6")),
                FieldSchema.Toggle("value", "Match exists"),
            ),
            keywords = setOf("ip", "local address", "interface", "ipv4", "ipv6"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val addressContains = feature.config.string("addressContains").resolveVariables(ctx.variables).trim()
            val interfaceContains = feature.config.string("interfaceContains").resolveVariables(ctx.variables).trim()
            val family = feature.config.string("family", "any")
            if (family !in setOf("any", "ipv4", "ipv6")) return@ConditionEvaluator false
            val snapshots = withContext(Dispatchers.IO) { collectLocalAddresses(includeLoopback = true, includeIpv6 = true) }
            val found = snapshots.any { snapshot ->
                (addressContains.isBlank() || snapshot.address.contains(addressContains, ignoreCase = true)) &&
                    (interfaceContains.isBlank() || snapshot.interfaceName.contains(interfaceContains, ignoreCase = true)) &&
                    when (family) {
                        "ipv4" -> !snapshot.ipv6
                        "ipv6" -> snapshot.ipv6
                        else -> true
                    }
            }
            found == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerTcpReachable(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "TCP port reachable", "Check whether a TCP connection can be established to a host and port within a bounded timeout",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Text("host", "Host", true),
                FieldSchema.Number("port", "Port", true, min = 1.0, max = 65535.0),
                FieldSchema.Duration("timeoutMs", "Connection timeout"),
                FieldSchema.Toggle("value", "Reachable"),
            ),
            keywords = setOf("tcp", "port", "reachable", "server", "socket"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val host = feature.config.string("host").resolveVariables(ctx.variables).trim()
            val port = feature.config["port"].numberOrNull()?.toInt() ?: return@ConditionEvaluator false
            val timeout = feature.config.long("timeoutMs", 3_000).coerceIn(100, 30_000).toInt()
            if (!isReasonableHost(host) || !isValidPort(port)) return@ConditionEvaluator false
            tcpReachable(host, port, timeout) == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerWaitForTcp(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.network.tcp.wait"), FeatureKind.ACTION,
                "Wait for TCP port", "Wait until a TCP host and port become reachable, with a strict total timeout",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("host", "Host", true),
                    FieldSchema.Number("port", "Port", true, min = 1.0, max = 65535.0),
                    FieldSchema.Duration("timeoutMs", "Total timeout"),
                    FieldSchema.Duration("intervalMs", "Retry interval"),
                    FieldSchema.Variable("resultVariable", "Optional result variable"),
                ),
                keywords = setOf("tcp", "wait", "server", "port", "online"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val host = feature.config.string("host").resolveVariables(ctx.variables).trim()
            val port = feature.config["port"].numberOrNull()?.toInt()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.network_port_invalid"))
            if (!isReasonableHost(host) || !isValidPort(port)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.network_host_port_invalid"))
            }
            val timeout = feature.config.long("timeoutMs", 30_000).coerceIn(250, 300_000)
            val interval = feature.config.long("intervalMs", 1_000).coerceIn(100, 30_000)
            val reached = waitForTcp(host, port, timeout, interval)
            val value = ConfigValue.BooleanValue(reached)
            feature.config.string("resultVariable").trim().takeIf { it.isNotEmpty() }?.let { ctx.variables.set(it, value) }
            ActionExecutionResult(reached, value, if (reached) null else userText("feature.network_wait_timeout", host, port))
        }
    }
}

internal data class LocalAddressSnapshot(
    val interfaceName: String,
    val address: String,
    val ipv6: Boolean,
    val loopback: Boolean,
)

internal fun collectLocalAddresses(includeLoopback: Boolean, includeIpv6: Boolean): List<LocalAddressSnapshot> {
    val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    return interfaces.asSequence()
        .filter { runCatching { it.isUp }.getOrDefault(false) }
        .flatMap { network ->
            network.inetAddresses.toList().asSequence().mapNotNull { address ->
                val ipv6 = address is Inet6Address
                val ipv4 = address is Inet4Address
                if (!ipv4 && !ipv6) return@mapNotNull null
                if (ipv6 && !includeIpv6) return@mapNotNull null
                if (address.isLoopbackAddress && !includeLoopback) return@mapNotNull null
                LocalAddressSnapshot(
                    interfaceName = network.name.orEmpty(),
                    address = address.hostAddress.orEmpty().substringBefore('%'),
                    ipv6 = ipv6,
                    loopback = address.isLoopbackAddress,
                )
            }
        }
        .filter { it.address.isNotBlank() }
        .sortedWith(compareBy<LocalAddressSnapshot> { it.interfaceName }.thenBy { it.address })
        .toList()
}

internal suspend fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)
}

internal suspend fun waitForTcp(host: String, port: Int, timeoutMs: Long, intervalMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        if (remainingMs <= 0) return false
        val connectTimeout = remainingMs.coerceAtMost(3_000).toInt().coerceAtLeast(100)
        if (tcpReachable(host, port, connectTimeout)) return true
        val delayMs = intervalMs.coerceAtMost(remainingMs)
        if (delayMs <= 0) return false
        delay(delayMs)
    }
}

internal fun isValidPort(port: Int): Boolean = port in 1..65535

internal fun isReasonableHost(host: String): Boolean =
    host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == ';' || it == '&' || it == '|' || it == '`' }
