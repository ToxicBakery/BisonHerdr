package com.toxicbakery.app.bison.mosh.ssp

import kotlin.random.Random

/**
 * A network with virtual time between one client and one server. Each datagram can go away, arrive two times, and
 * arrive late. A random delay changes the order of the datagrams.
 *
 * The client has an address. A new socket gets a new address. A datagram to an old address goes away, because
 * the old socket is closed.
 */
internal class SimulatedNetwork(
    private val random: Random,
    var lossRate: Double = 0.0,
    var duplicateRate: Double = 0.0,
    var delayMs: Long = 20,
    var jitterMs: Long = 0,
) {
    private class Delivery(val atMs: Long, val toServer: Boolean, val address: Int, val packet: ByteArray)

    // Sorted by time. Two datagrams with the same time keep the send order.
    private val queue = ArrayList<Delivery>()

    /** When true, each datagram from the server to the client goes away. */
    var blockToClient: Boolean = false

    /** When true, each datagram from the client to the server goes away. */
    var blockToServer: Boolean = false

    /** The count of datagrams that the client sent. */
    var clientDatagrams: Int = 0
        private set

    /** Sends [packet] from the client at [address] to the server. */
    fun sendToServer(packet: ByteArray, address: Int, nowMs: Long) {
        clientDatagrams++
        if (!blockToServer) schedule(packet, toServer = true, address = address, nowMs = nowMs)
    }

    /** Sends [packet] from the server to the client at [address]. */
    fun sendToClient(packet: ByteArray, address: Int, nowMs: Long) {
        if (!blockToClient) schedule(packet, toServer = false, address = address, nowMs = nowMs)
    }

    /** The time of the next delivery, or [Long.MAX_VALUE]. */
    val nextDeliveryMs: Long get() = queue.firstOrNull()?.atMs ?: Long.MAX_VALUE

    /** Calls [toServer] or [toClient] for each datagram that arrives at [nowMs] or before. */
    fun deliver(nowMs: Long, toServer: (ByteArray, Int) -> Unit, toClient: (ByteArray, Int) -> Unit) {
        while (queue.isNotEmpty() && queue.first().atMs <= nowMs) {
            val delivery = queue.removeAt(0)
            if (delivery.toServer) {
                toServer(delivery.packet, delivery.address)
            } else {
                toClient(delivery.packet, delivery.address)
            }
        }
    }

    private fun schedule(packet: ByteArray, toServer: Boolean, address: Int, nowMs: Long) {
        if (random.nextDouble() < lossRate) return
        val copies = if (random.nextDouble() < duplicateRate) 2 else 1
        repeat(copies) {
            val atMs = nowMs + delayMs + if (jitterMs > 0) random.nextLong(jitterMs + 1) else 0
            val delivery = Delivery(atMs, toServer, address, packet)
            val index = queue.indexOfFirst { queued -> queued.atMs > atMs }.let { if (it < 0) queue.size else it }
            queue.add(index, delivery)
        }
    }
}
