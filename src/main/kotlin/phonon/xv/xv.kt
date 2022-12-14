/**
 * Contain main engine global state and core api.
 */

package phonon.xv

import java.nio.file.Paths
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.xv.core.*
import phonon.xv.system.*
import phonon.xv.common.UserInput
import phonon.xv.util.file.listDirFiles
import java.util.LinkedList
import java.util.Queue

public const val MAX_VEHICLES = 5000

/**
 * XV engine global state.
 * Stores all game state and provide XV engine api.
 */
public object XV {
    // spigot plugin variable
    internal var plugin: Plugin? = null
    internal var logger: Logger? = null

    // ========================================================================
    // STORAGE
    // ========================================================================
    internal var config: Config = Config()
    
    // vehicle base prototypes
    internal var vehiclePrototypes: Map<String, VehiclePrototype> = mapOf()
    internal var vehiclePrototypeNames: List<String> = listOf() // for tab completion
    // vehicle element prototypes
    // the keys in this map are <vehicle.name>.<element.name>, not just the element name
    internal var vehicleElementPrototypes: Map<String, VehicleElementPrototype> = mapOf()
    // list of <vehicle.name>.<element.name>
    internal var vehicleElementPrototypeNames: List<String> = listOf()

    // components
    internal val storage: ComponentsStorage = ComponentsStorage()
    internal val vehicleStorage: VehicleStorage = VehicleStorage(MAX_VEHICLES)

    // user input controls when mounted on entities
    internal val userInputs: HashMap<UUID, UserInput> = HashMap()

    // entity uuid => vehicle element data
    internal val entityVehicleData: HashMap<UUID, EntityVehicleData> = HashMap()

    // element uuid -> element
    internal val uuidToElement: HashMap<UUID, VehicleElement> = HashMap()
    // vehicle uuid -> element
    internal val uuidToVehicle: HashMap<UUID, Vehicle> = HashMap()

    // player mount and dismount requests
    internal var mountRequests: ArrayList<MountVehicleRequest> = ArrayList()
    internal var dismountRequests: ArrayList<DismountVehicleRequest> = ArrayList()
    // vehicle creation requests
    internal var createRequests: Queue<CreateVehicleRequest> = LinkedList()

    // ========================================================================
    // RUNNING TASKS
    // ========================================================================
    internal var engineTask: BukkitTask? = null

    /**
     * onEnable:
     * Set links to spigot plugin and logger.
     */
    internal fun onEnable(plugin: Plugin) {
        XV.plugin = plugin
        XV.logger = plugin.getLogger()
    }

    /**
     * Remove hooks to plugins and external APIs
     */
    internal fun onDisable() {
        XV.plugin = null
        XV.logger = null
    }

    /**
     * Re-initialize storages and re-load config.
     * TODO: async
     */
    internal fun reload() {
        val timeStart = System.currentTimeMillis()

        // load main plugin config
        val pathConfigToml = Paths.get(XV.plugin!!.getDataFolder().getPath(), "config.toml")
        val config = if ( Files.exists(pathConfigToml) ) {
            Config.fromToml(pathConfigToml, XV.logger)
        } else {
            XV.logger!!.info("Creating default config.toml")
            XV.plugin!!.saveResource("config.toml", false)
            Config()
        }

        XV.config = config

        // clear current component/archetype storage
        storage.clear()

        // load vehicle config files

        // wtf why isnt it saving this shit automatically??
        listOf(
            "vehicle/debug_car.toml",
            "vehicle/debug_multi_turret.toml",
            "vehicle/debug_tank.toml",
        ).forEach { p -> XV.plugin!!.saveResource(p, false) }

        val elementPrototypes = mutableMapOf<String, VehicleElementPrototype>()

        val vehiclePrototypes: Map<String, VehiclePrototype> = listDirFiles(config.pathFilesVehicles)
            .map { f -> VehiclePrototype.fromTomlFile(config.pathFilesVehicles.resolve(f), XV.logger) }
            .filterNotNull()
            .map { v -> // add layouts of each element prototype
                // perhaps we can relegate this to when vehicles are spawned
                // first vehicle to spawn w/ new layout gets ArchetypeStorage for that
                // layout added on creation
                v.elements.forEach { e ->
                    if (!storage.lookup.containsKey(e.layout))
                        storage.addLayout(e.layout)
                    elementPrototypes["${v.name}.${e.name}"] = e
                }
                v.name to v
            }
            .toMap()

        XV.vehicleElementPrototypes = elementPrototypes
        XV.vehicleElementPrototypeNames = XV.vehicleElementPrototypes.keys.toList()
        XV.vehiclePrototypes = vehiclePrototypes
        XV.vehiclePrototypeNames = vehiclePrototypes.keys.toList()
        
        // finish: print stats
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        XV.logger?.info("Reloaded in ${timeLoad}ms")
        XV.logger?.info("- Prototypes: ${vehiclePrototypes.size}")
    }

    /**
     * Starts running engine task
     */
    internal fun start() {
        if ( XV.engineTask == null ) {
            XV.engineTask = Bukkit.getScheduler().runTaskTimer(XV.plugin!!, object: Runnable {
                override fun run() {
                    XV.update()
                }
            }, 0, 0)

            XV.logger!!.info("Starting engine")
        }
        else {
            XV.logger!!.warning("Engine already running")
        }
    }

    /**
     * Stop running engine task
     */
    internal fun stop() {
        val task = XV.engineTask
        if ( task != null ) {
            task.cancel()
            XV.engineTask = null
            XV.logger!!.info("Stopping engine")
        } else {
            XV.logger!!.warning("Engine not running")
        }
    }

    /**
     * Main engine update, runs on each tick
     * 
     * TODO: MULTI-WORLD
     * If we want vehicles in overworld, nether, end...
     * Need to run each world simulation separately
     * Make per world storages and request queues
     *     World => {
     *      storage: ComponentsStorage,
     *      playerMountRequests: ArrayList<MountVehicleRequest>,
     *      ...
     *     }
     * (Some stuff still shared, like global UserInputs)
     * 
     * TODO: EVENTUAL OPTIMIZATION
     * We will have MANY systems, some of which will be extremely specific
     * for vehicle combinations that may not even exist in configs.
     * An optimization is to convert systems into a singleton functions
     * with layout metadata and/or queries, e.g.
     * 
     * interface System {
     *   val layout: EnumSet<VehicleComponentType>?  // describes what components needed
     *   var valid: Boolean   // cache if system valid for current config archetypes
     * 
     *   fun execute() {
     *     // existing system function
     *   }
     * }
     * 
     * object SystemLandMovement: System {
     *    override val layout = EnumSet.of(
     *        VehicleComponentType.TRANSFORM,
     *        VehicleComponentType.MODEL,
     *        VehicleComponentType.LAND_MOVEMENT_CONTROLS,
     *   )
     *   override val valid = true // engine will update this
     *   
     *   override fun execute() { ... }
     * }
     * 
     * And eventually convert update into a Schedule object that is
     * re-created each time engine is reloaded, which updates each
     * system's metadata to turn off invalid systems.
     */
    internal fun update() {
        // mount and dismount request handlers
        mountRequests = systemMountVehicle(storage, mountRequests) // filters direct interaction mounting
        dismountRequests = systemDismountVehicle(storage, dismountRequests)

        // player vehicle movement controls
        systemLandMovement(storage, userInputs)

        // update vehicle models after transforms updated
        // MUST BE RUN AFTER ALL MOVEMENT CONTROLLERS
        systemUpdateModels(storage)
        systemUpdateSeats(storage, userInputs)

        // seat raycast mounting
        mountRequests = systemMountSeatRaycast(storage, mountRequests)

        // create vehicle handlers
        systemCreateVehicle(storage, createRequests)
    }
}