import RoomScanner.serialize
import algorithm.AbstractAlgorithm
import algorithm.AlgorithmThread
import algorithm.RoomBypassingAlgorithm
import algorithm.RoomModel
import net.car.client.Client
import objects.Car
import objects.Environment
import java.net.ConnectException
import java.rmi.UnexpectedException

object DebugClInterface {

    //todo remove
    var algorithmImpl: AbstractAlgorithm? = null
    var algorithmThread: AlgorithmThread? = null

    private val routeRegex = Regex("route [0-9]{1,10}")
    private val sonarRegex = Regex("sonar [0-9]{1,10}")
    private val helpString = "available handlers:\n" +
            "cars - get list of connected cars\n" +
            "route [car_id] - setting a route for net.car with net.car id.\n" +
            "sonar [car_id] - get sonar data\n" +
            "dbinfo [car_id] [type] - refresh all net.car locations\n" +
            "lines - print lines, detected by net.car\n" +
            "alg [count] - run algorithm. Make [count] iteration\n" +
            "type is string name of value or int value. available values: MEMORYSTATS - 0\n" +
            "stop - exit from this interface and stop all threads\n"

    fun run() {

        println(helpString)
        while (true) {
            val readString = readLine()
            if (readString == null || readString.equals("stop")) {
                break
            }
            if (readString.equals("")) {
                continue
            }

            try {
                executeCommand(readString)
            } catch (exception: Exception) {
                exception.printStackTrace()
                println("Fail to execute command[$readString]: $exception")
            }
        }
    }

    private fun printNotSupportedCommand(command: String) {
        println("not supported command: $command")
        println(helpString)
    }

    private fun executeCommand(readString: String) {
        val commandType = readString.split(" ")[0].toLowerCase()
        when (commandType) {
            "cars" -> {
                synchronized(Environment, {
                    println(Environment.map.values)
                })
            }
            "route" -> executeRouteCommand(readString)
            "sonar" -> executeSonarCommand(readString)
            "dbinfo" -> executeDebugInfoCommand(readString)
            "lines" -> algorithm.RoomModel.walls.forEach { println(it.line) }
            "pos" -> {
                val tmp = algorithmImpl
                if (tmp is RoomBypassingAlgorithm) {
                    println("walls: ${RoomModel.walls}")
                    println("x: ${tmp.thisCar.x} y: ${tmp.thisCar.y} angle:${tmp.thisCar.angle}")
                }
            }
            "alg" -> executeAlg(readString)
            "explore" -> executeExplore(readString)
            else -> printNotSupportedCommand(readString)
        }
    }

    private fun executeExplore(readString: String) {
        val params = readString.split(" ")
        val car = Environment.map[params[1].toInt()]!!
        val angle = params[2].toInt()
        val window = params[3].toInt()

        val request = SonarExploreAngleRequest.BuilderSonarExploreAngleRequest(angle, window).build()
        val responseData = car.carConnection.sendRequest(
                Client.Request.EXPLORE_ANGLE,
                serialize(request.getSizeNoTag(), { request.writeTo(it) })
        ).get().responseBodyAsBytes

        val distances = SonarExploreAngleResponse.BuilderSonarExploreAngleResponse(IntArray(0)).parseFrom(CodedInputStream(responseData)).build().distances

        println("Received distances: [${distances.joinToString()}]")
    }

    private fun executeAlg(readString: String) {
        val params = readString.split(" ")
        val count =
                if (params.size == 2) try {
                    params[1].toInt()
                } catch (e: Exception) {
                    1
                } else 1

        var alg = algorithmImpl
        if (alg == null) {
            alg = RoomBypassingAlgorithm(Environment.map.values.last())
        }
        var algThread = algorithmThread
        if (algThread == null) {
            algThread = AlgorithmThread(alg)
            algThread.start()
        }
        algorithmImpl = alg
        algThread.setCount(count)
        algorithmThread = algThread
    }

    private fun executeDebugInfoCommand(readString: String) {
        val params = readString.split(" ")
        val car = Environment.map[params[1].toInt()]!!
        val type = DebugRequest.Type.fromIntToType(params[2].toInt())

        val request = DebugRequest.BuilderDebugRequest(type).build()
        val requestType = when (type) {
            DebugRequest.Type.MEMORY_STATS -> Client.Request.DEBUG_MEMORY
            DebugRequest.Type.SONAR_STATS -> Client.Request.DEBUG_SONAR
            else -> throw UnexpectedException(type.toString())
        }

        val responseData = car.carConnection.sendRequest(
                requestType,
                serialize(request.getSizeNoTag(), { request.writeTo(it) })
        ).get().responseBodyAsBytes

        when (type) {
            DebugRequest.Type.MEMORY_STATS -> {
                val data = DebugResponseMemoryStats.BuilderDebugResponseMemoryStats(0, 0, 0, 0).parseFrom(CodedInputStream(responseData)).build()
                println("Heap static tail: ${data.heapStaticTail}")
                println("Heap dynamic tail: ${data.heapDynamicTail}")
                println("Heap dynamic max size: ${data.heapDynamicMaxBytes}")
                println("Heap dynamic total size: ${data.heapDynamicTotalBytes}")
            }
            DebugRequest.Type.SONAR_STATS -> {
                val data = DebugResponseSonarStats.BuilderDebugResponseSonarStats(0, 0, 0, 0).parseFrom(CodedInputStream(responseData)).build()
                println("Sonar measurement total: ${data.measurementCount}")
                println("Failed checksums: ${data.measurementFailedChecksum}")
                println("Failed command: ${data.measurementFailedCommand}")
                println("Failed distance: ${data.measurementFailedDistance}")
            }
            else -> throw UnexpectedException(type.toString())
        }
    }

    private fun executeSonarCommand(readString: String) {
        if (!sonarRegex.matches(readString)) {
            println("incorrect args of command sonar.")
            println(helpString)
            return
        }
        val id: Int
        try {
            id = readString.split(" ")[1].toInt()
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            println("error in converting id to int type")
            println(helpString)
            return
        }
        val car: Car? = synchronized(Environment, {
            Environment.map[id]
        })
        if (car == null) {
            println("net.car with id=$id not found")
            return
        }
        val requestMessage = getSonarRequest() ?: return
        val requestBytes = ByteArray(requestMessage.getSizeNoTag())
        requestMessage.writeTo(CodedOutputStream(requestBytes))
        try {
            car.carConnection.sendRequest(Client.Request.SONAR, requestBytes)
        } catch (e: ConnectException) {
            synchronized(Environment, {
                Environment.map.remove(id)
            })
        }
    }

    private fun getSonarRequest(): SonarRequest? {
        println("print angles, after printing all angles print done")
        val angles = arrayListOf<Int>()
        while (true) {
            val command = readLine()!!.toLowerCase()
            when (command) {
                "reset" -> return null
                "done" -> {
                    val sonarBuilder = SonarRequest.BuilderSonarRequest(angles.toIntArray(), IntArray(angles.size, { 5 }), 3, SonarRequest.Smoothing.MEDIAN)
                    return sonarBuilder.build()
                }
                else -> {
                    try {
                        val angle = command.toInt()
                        if (angle < 0 || angle > 180) {
                            println("incorrect angle $angle. angle must be in [0,180] and div on 4")
                        } else {
                            angles.add(angle)
                        }
                    } catch (e: NumberFormatException) {
                        println("error in converting angle to int. try again")
                    }
                }
            }
        }
    }

    private fun executeRouteCommand(readString: String) {
        if (!routeRegex.matches(readString)) {
            println("incorrect args of command route.")
            println(helpString)
            return
        }
        val id: Int
        try {
            id = readString.split(" ")[1].toInt()
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            println("error in converting id to int type")
            println(helpString)
            return
        }
        val car: Car? =
                synchronized(Environment, {
                    Environment.map[id]
                })
        if (car == null) {
            println("net.car with id=$id not found")
            return
        }
        val routeMessage = getRouteMessage() ?: return
        val requestBytes = ByteArray(routeMessage.getSizeNoTag())
        routeMessage.writeTo(CodedOutputStream(requestBytes))
        try {
            car.carConnection.sendRequest(Client.Request.ROUTE_METRIC, requestBytes)
        } catch (e: ConnectException) {
            synchronized(Environment, {
                Environment.map.remove(id)
            })
        }
    }

    private fun getRouteMessage(): RouteRequest? {
        println("print way points in polar coordinate als [distance/degrees] [direction] in sm and degrees." +
                "after enter all points print \"done\". for reset route print \"reset\". available directions:" +
                "0 - FORWARD, 1 - BACKWARD, 2 - LEFT, 3 - RIGHT")
        val distances = arrayListOf<Int>()
        val angles = arrayListOf<Int>()
        while (true) {
            val readLine = readLine()!!.toLowerCase()
            when (readLine) {
                "reset" -> return null
                "done" -> {
                    val routeBuilder = RouteRequest.BuilderRouteRequest(distances.toIntArray(), angles.toIntArray())
                    return routeBuilder.build()
                }
                else -> {
                    val wayPointData = readLine.split(" ")
                    val distance: Int
                    val angle: Int
                    try {
                        distance = wayPointData[0].toInt()
                        angle = wayPointData[1].toInt()
                        distances.add(distance)
                        angles.add(angle)
                    } catch (e: NumberFormatException) {
                        println("error in converting target or distance to int. try again")
                    } catch (e: IndexOutOfBoundsException) {
                        println("format error, u must print two number separated by spaces. Try again")
                    }
                }
            }
        }
    }
}
