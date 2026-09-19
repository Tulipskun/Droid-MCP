package com.tulipskun.droidmcp

import android.content.SharedPreferences
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

internal class AdbTransport(
    private val preferences: SharedPreferences
) : Closeable {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextLocalId = 1

    fun connect() {
        if (socket != null && socket!!.isConnected && !socket!!.isClosed) return

        close()

        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.keepAlive = true
        newSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
        newSocket.connect(
            InetSocketAddress(ADB_HOST, ADB_PORT),
            CONNECT_TIMEOUT_MS
        )

        socket = newSocket
        input = DataInputStream(BufferedInputStream(newSocket.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))

        sendPacket(
            A_CNXN,
            A_VERSION,
            MAX_PAYLOAD,
            "host::".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        )

        val key = loadOrCreateKey()
        var signatureSent = false
        var publicKeySent = false

        while (true) {
            val packet = readPacket()

            when (packet.command) {
                A_CNXN -> {
                    newSocket.soTimeout = 0
                    return
                }

                A_AUTH -> {
                    when (packet.arg0) {
                        AUTH_TOKEN -> {
                            if (!signatureSent) {
                                sendPacket(
                                    A_AUTH,
                                    AUTH_SIGNATURE,
                                    0,
                                    signToken(packet.payload, key)
                                )
                                signatureSent = true
                            } else if (!publicKeySent) {
                                sendPacket(
                                    A_AUTH,
                                    AUTH_PUBLIC_KEY,
                                    0,
                                    publicKeyPayload(key)
                                )
                                publicKeySent = true
                            } else {
                                sendPacket(
                                    A_AUTH,
                                    AUTH_SIGNATURE,
                                    0,
                                    signToken(packet.payload, key)
                                )
                            }
                        }

                        else -> throw AdbException(
                            "Unsupported ADB authentication request: " + packet.arg0
                        )
                    }
                }

                else -> throw AdbException(
                    "Unexpected ADB handshake packet: " + commandName(packet.command)
                )
            }
        }
    }

    fun open(service: String): AdbStream {
        connect()

        val localId = nextLocalId++
        val payload =
            service.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)

        sendPacket(A_OPEN, localId, 0, payload)

        while (true) {
            val packet = readPacket()

            when (packet.command) {
                A_OKAY -> {
                    if (packet.arg1 != localId) {
                        throw AdbException("ADB OPEN reply id mismatch")
                    }
                    return AdbStream(this, localId, packet.arg0)
                }

                A_CLSE -> throw AdbException(
                    "ADB service rejected: " + service
                )

                A_WRTE -> {
                    sendPacket(A_OKAY, localId, packet.arg0, byteArrayOf())
                }

                else -> throw AdbException(
                    "Unexpected ADB OPEN response: " +
                        commandName(packet.command)
                )
            }
        }
    }

    internal fun writeStream(
        localId: Int,
        remoteId: Int,
        payload: ByteArray
    ) {
        require(payload.size <= MAX_PAYLOAD) {
            "ADB payload too large"
        }
        sendPacket(A_WRTE, localId, remoteId, payload)
    }

    internal fun readPacket(): Packet {
        val inputStream = input
            ?: throw AdbException("ADB transport is not connected")

        val command = inputStream.readIntLE()
        val arg0 = inputStream.readIntLE()
        val arg1 = inputStream.readIntLE()
        val dataLength = inputStream.readIntLE()
        val checksum = inputStream.readIntLE()
        val magic = inputStream.readIntLE()

        if (magic != (command xor -1)) {
            throw AdbException("Invalid ADB packet magic")
        }

        if (dataLength < 0 || dataLength > MAX_PAYLOAD) {
            throw AdbException(
                "Invalid ADB payload length: " + dataLength
            )
        }

        val payload = ByteArray(dataLength)
        inputStream.readFully(payload)

        if (checksum != checksum(payload)) {
            throw AdbException("ADB payload checksum mismatch")
        }

        return Packet(command, arg0, arg1, payload)
    }

    private fun sendPacket(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val outputStream = output
            ?: throw AdbException("ADB transport is not connected")

        if (payload.size > MAX_PAYLOAD) {
            throw AdbException(
                "ADB payload too large: " + payload.size
            )
        }

        outputStream.writeIntLE(command)
        outputStream.writeIntLE(arg0)
        outputStream.writeIntLE(arg1)
        outputStream.writeIntLE(payload.size)
        outputStream.writeIntLE(checksum(payload))
        outputStream.writeIntLE(command xor -1)
        outputStream.write(payload)
        outputStream.flush()
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        socket = null
        input = null
        output = null
        nextLocalId = 1
    }

    private fun loadOrCreateKey(): KeyPair {
        val encoded = preferences.getString(KEY_PRIVATE, null)

        if (!encoded.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(bytes))
                val rsaPrivate = privateKey as RSAPrivateKey
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(
                        rsaPrivate.modulus,
                        BigInteger.valueOf(65537)
                    )
                ) as RSAPublicKey

                return KeyPair(publicKey, privateKey)
            } catch (_: Throwable) {
            }
        }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()

        preferences.edit()
            .putString(
                KEY_PRIVATE,
                Base64.encodeToString(
                    pair.private.encoded,
                    Base64.NO_WRAP
                )
            )
            .apply()

        return pair
    }

    private fun signToken(
        token: ByteArray,
        key: KeyPair
    ): ByteArray {
        if (token.size != AUTH_TOKEN_SIZE) {
            throw AdbException(
                "Invalid ADB auth token size: " + token.size
            )
        }

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(key.private)
        signature.update(token)
        return signature.sign()
    }

    private fun publicKeyPayload(key: KeyPair): ByteArray {
        val publicKey = key.public as RSAPublicKey
        val modulus = littleEndianFixed(
            publicKey.modulus,
            RSA_MODULUS_BYTES
        )

        val buffer = ByteBuffer
            .allocate(
                4 + 4 +
                    RSA_MODULUS_BYTES +
                    RSA_MODULUS_BYTES +
                    4
            )
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(RSA_MODULUS_BYTES / 4)
        buffer.putInt(0)
        buffer.put(modulus)
        buffer.put(ByteArray(RSA_MODULUS_BYTES))
        buffer.putInt(publicKey.publicExponent.intValueExact())

        val encoded = Base64.encodeToString(
            buffer.array(),
            Base64.NO_WRAP
        )

        return (
            encoded + " droid-mcp@android"
        ).toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
    }

    private fun littleEndianFixed(
        value: BigInteger,
        size: Int
    ): ByteArray {
        val raw = value.toByteArray()
        val unsigned = if (
            raw.size > 1 &&
            raw[0].toInt() == 0
        ) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }

        require(unsigned.size <= size)

        val result = ByteArray(size)

        for (i in unsigned.indices) {
            result[i] =
                unsigned[unsigned.lastIndex - i]
        }

        return result
    }

    private fun checksum(payload: ByteArray): Int {
        var value = 0
        for (byte in payload) {
            value += byte.toInt() and 0xff
        }
        return value
    }

    private fun commandName(command: Int): String {
        return when (command) {
            A_CNXN -> "CNXN"
            A_AUTH -> "AUTH"
            A_OPEN -> "OPEN"
            A_OKAY -> "OKAY"
            A_CLSE -> "CLSE"
            A_WRTE -> "WRTE"
            else -> "0x" + command.toUInt().toString(16)
        }
    }

    data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    class AdbException(
        message: String
    ) : IllegalStateException(message)

    companion object {
        const val ADB_HOST = "127.0.0.1"
        const val ADB_PORT = 5555

        private const val CONNECT_TIMEOUT_MS = 3000
        private const val HANDSHAKE_TIMEOUT_MS = 15000

        private const val A_CNXN = 0x4e584e43
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_AUTH = 0x48545541

        private const val A_VERSION = 0x01000001
        private const val MAX_PAYLOAD = 1024 * 1024

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_PUBLIC_KEY = 3
        private const val AUTH_TOKEN_SIZE = 20

        private const val RSA_MODULUS_BYTES = 256
        private const val KEY_PRIVATE = "adb_private_key"

        private fun DataInputStream.readIntLE(): Int {
            return Integer.reverseBytes(readInt())
        }

        private fun DataOutputStream.writeIntLE(value: Int) {
            writeInt(Integer.reverseBytes(value))
        }
    }
}

internal class AdbStream(
    private val transport: AdbTransport,
    private val localId: Int,
    private val remoteId: Int
) {
    private val shellPending = ByteArrayOutputStream()

    fun writeShellInput(data: ByteArray) {
        val packet = ByteBuffer
            .allocate(5 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putInt(data.size)
            .put(data)
            .array()

        transport.writeStream(localId, remoteId, packet)
    }

    fun readUntilMarker(marker: String): String {
        val output = StringBuilder()

        while (true) {
            val packet = transport.readPacket()

            when (packet.command) {
                0x45545257 -> {
                    transport.writeStream(
                        localId,
                        packet.arg0,
                        byteArrayOf()
                    )

                    consumeShellPayload(
                        packet.payload,
                        output
                    )

                    if (output.contains(marker)) {
                        return output.toString()
                    }
                }

                0x59414b4f -> {
                    if (packet.arg1 != localId) {
                        throw AdbTransport.AdbException(
                            "ADB stream reply id mismatch"
                        )
                    }
                }

                0x45534c43 -> {
                    try {
                        transport.writeStream(
                            localId,
                            packet.arg0,
                            byteArrayOf()
                        )
                    } catch (_: Throwable) {
                    }

                    throw AdbTransport.AdbException(
                        "ADB shell session closed"
                    )
                }

                else -> throw AdbTransport.AdbException(
                    "Unexpected ADB stream packet"
                )
            }
        }
    }

    fun readRawUntilClose(): ByteArray {
        val output = ByteArrayOutputStream()

        while (true) {
            val packet = transport.readPacket()

            when (packet.command) {
                0x45545257 -> {
                    transport.writeStream(
                        localId,
                        packet.arg0,
                        byteArrayOf()
                    )
                    output.write(packet.payload)
                }

                0x59414b4f -> {
                    if (packet.arg1 != localId) {
                        throw AdbTransport.AdbException(
                            "ADB stream reply id mismatch"
                        )
                    }
                }

                0x45534c43 -> {
                    try {
                        transport.writeStream(
                            localId,
                            packet.arg0,
                            byteArrayOf()
                        )
                    } catch (_: Throwable) {
                    }

                    return output.toByteArray()
                }

                else -> throw AdbTransport.AdbException(
                    "Unexpected ADB stream packet"
                )
            }
        }
    }

    fun close() {
        try {
            transport.writeStream(
                localId,
                remoteId,
                byteArrayOf()
            )
        } catch (_: Throwable) {
        }
    }

    private fun consumeShellPayload(
        payload: ByteArray,
        output: StringBuilder
    ) {
        shellPending.write(payload)

        while (shellPending.size() >= 5) {
            val bytes = shellPending.toByteArray()
            val type = bytes[0].toInt() and 0xff
            val length = ByteBuffer
                .wrap(bytes, 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            if (length < 0 || length > 1024 * 1024) {
                throw AdbTransport.AdbException(
                    "Invalid shell protocol packet length"
                )
            }

            if (bytes.size < 5 + length) return

            val data = bytes.copyOfRange(
                5,
                5 + length
            )

            shellPending.reset()

            shellPending.write(
                bytes,
                5 + length,
                bytes.size - 5 - length
            )

            when (type) {
                1, 2 -> output.append(
                    String(
                        data,
                        StandardCharsets.UTF_8
                    )
                )

                3 -> Unit
            }
        }
    }
}
