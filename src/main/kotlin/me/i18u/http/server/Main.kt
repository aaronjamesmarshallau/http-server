package me.i18u.http.server

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.raise.either
import me.i18u.http.server.Error.ParseError
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

interface RequestRunner {
    fun run(request: HttpRequest): HttpResponse;
}

sealed class Error(msg: String, throwable: Option<Throwable>) {
    data class ParseError(val msg: String, val throwable: Option<Throwable>) : Error(msg, throwable)
}

class HttpResponse {

}

data class HttpVersion(val major: Int, val minor: Int) {
    companion object {
        fun parse(rawVersion: String): Either<ParseError, HttpVersion> {
            val (rawMajor, rawMinor) = rawVersion.split('/')[1].split('.')
            val major = rawMajor.toInt()
            val minor = rawMinor.toInt()

            return Right(HttpVersion(major, minor))
        }
    }
}

data class HttpRequest(
    val version: HttpVersion,
    val uri: URI,
    val method: HttpMethod,
    val headers: Map<String, List<String>>
)

data class RawHttpRequest(
    val headers: List<String>,
    val body: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawHttpRequest

        if (headers != other.headers) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

class HttpHandler(val routes: Map<HttpMethod, Map<String, RequestRunner>>) {
    val contentLength = "\r\nContent-Length:"

    fun readMessage(inputStream: InputStream): RawHttpRequest {
        try {
            val inBuffer = ByteArray(64)
            val bufferedReader = BufferedInputStream(inputStream)

            // Read the data into the inBuffer
            var read: Int = bufferedReader.read(inBuffer)
            var dataString = ""

            println("About to read")

            while (read != -1) {
                println("Read $read bytes")
                val newDataChunkString = String(inBuffer, 0, read)
                val doubleLineBreakIndex = newDataChunkString.indexOf("\r\n\r\n")

                dataString += newDataChunkString

                // Headers have been sent and there's no more header data expected
                if (doubleLineBreakIndex != -1) {
                    // Replace inBuffer with remaining inBuffer
                    inBuffer.copyInto(inBuffer, 0, doubleLineBreakIndex, inBuffer.size)
                    break;
                }

                val lastThreeExistingMessage = dataString.substring(dataString.length - 3, dataString.length)
                val firstThreeNewChunk = newDataChunkString.substring(0, 3)
                val seam = lastThreeExistingMessage + firstThreeNewChunk
                val seamBreakIndex = seam.indexOf("\r\n\r\n")

                if (seamBreakIndex != -1) {
                    val offset = seamBreakIndex + 1
                    inBuffer.copyInto(inBuffer, 0, offset, inBuffer.size)
                    break;
                }

                read = bufferedReader.read(inBuffer)
            }

            val contentLengthIndex = dataString.indexOf("Content-Length", 0, true)
            var expectedContentLength = -1

            if (contentLengthIndex != -1) {
                var contentLengthValueIndex = contentLengthIndex + contentLength.length
                var char = dataString[contentLengthValueIndex]

                while (char == ' ') {
                    contentLengthValueIndex++
                    char = dataString[contentLengthValueIndex]
                }

                while (dataString[contentLengthValueIndex] != '\r') {
                    char = dataString[contentLengthValueIndex]

                    if (char.code >= 48 || char.code <= 57) {
                        expectedContentLength = expectedContentLength * 10 + char.code - 48
                    }
                }
            }

            val body = ByteArrayOutputStream()
            var bytesWritten = 0

            while (read != -1 && expectedContentLength != -1) {
                body.write(inBuffer)
                bytesWritten = inBuffer.size

                println("Reading body")

                if (bytesWritten == expectedContentLength) {
                    break
                }

                read = bufferedReader.read(inBuffer)
            }

            println(dataString)

            return RawHttpRequest(
                dataString.split("\r\n"),
                body.toByteArray()
            )
        } catch (ex: Exception) {
            val stack = ex.stackTrace.joinToString("\n\t")
            println("Exception in HttpHandler: ${ex.message}\n${stack}")
            return RawHttpRequest(
                listOf(),
                byteArrayOf()
            )
        }
    }

    fun parseRequest(lines: List<String>): Either<ParseError, HttpRequest> {
        if (lines.isEmpty()) {
            return Left(ParseError("Unable to parse message: unexpected EOF", None))
        }

        val firstLine = lines[0]
        val (rawMethod, rawPath, rawVersion) = firstLine.split(" ")

        return either {
            val method = HttpMethod.parse(rawMethod).bind()
            val path = URI.create(rawPath)
            val version = HttpVersion.parse(rawVersion).bind()

            val mutableMap = HashMap<String, MutableList<String>>()

            for (lineIndex in 1..lines.lastIndex) {
                val line = lines[lineIndex]

                if (line.isEmpty()) {
                    // break -- end of headers, start of request body
                    break
                }

                val colonIndex = line.indexOf(':')

                if (colonIndex == -1) {
                    // message is malformed, stop processing
                    break
                }

                val key = line.substring(0, colonIndex)
                val rawValue = line.substring(colonIndex + 1, line.lastIndex + 1)

                val value = if (rawValue[0] == ' ') {
                    rawValue.substring(1, rawValue.lastIndex + 1)
                } else {
                    rawValue
                }

                if (!mutableMap.contains(key)) {
                    mutableMap[key] = mutableListOf()
                }

                mutableMap[key]?.add(value)
            }

            HttpRequest(
                version,
                path,
                method,
                mutableMap
            )
        }
    }

    fun handleConnection(inputStream: InputStream, outputStream: OutputStream): Either<Error, Unit> {
        return either {
            val requestMessage = readMessage(inputStream)
            val headers = parseRequest(requestMessage.headers).bind()

            val method = headers.method
            val uri = headers.uri

            val maybeMatchingRoute = Option.fromNullable(routes[method]?.get(uri.path))

            when (maybeMatchingRoute) {
                is None -> {
                    println("No matching route")
                    outputStream.write("404 Not Found".toByteArray(Charsets.US_ASCII))
                    outputStream.flush()
                    outputStream.close()
                }
                is Some -> println("Matching route")
            }
        }
    }
}

enum class HttpMethod {
    CONNECT,
    DELETE,
    GET,
    HEAD,
    OPTIONS,
    PATCH,
    POST,
    PUT,
    TRACE;

    companion object {
        fun parse(rawMethod: String): Either<ParseError, HttpMethod> {
            val method = when (rawMethod) {
                "CONNECT" -> CONNECT
                "DELETE" -> DELETE
                "GET" -> GET
                "HEAD" -> HEAD
                "OPTIONS" -> OPTIONS
                "PATCH" -> PATCH
                "POST" -> POST
                "PUT" -> PUT
                "TRACE" -> TRACE
                else -> return Left(
                    ParseError("Failed to parse METHOD from Request-Line", None)
                )
            }

            return Right(method)
        }
    }
}

class Server {
    private val routes: Map<HttpMethod, Map<String, RequestRunner>> = mapOf();
    private val socket: ServerSocket;
    private val threadPool: Executor;

    constructor(port: Int) {
        socket = ServerSocket(port)
        threadPool = Executors.newFixedThreadPool(100)
    }

    fun start() {
        val handler = HttpHandler(this.routes)

        while (true) {
            val clientConnection = socket.accept()

            try {
                handler.handleConnection(
                    clientConnection.inputStream,
                    clientConnection.outputStream
                )
            } catch (exception: IOException) {

            }
        }
    }
}

fun main() {
    val server = Server(3000)

    println("Main")

    server.start()
}