/*
 * XC Engine/API
 * 
 * A minimal minecraft custom combat engine.
 * To support guns, melee, hats, and vehicles.
 * Intended for personal server.
 */

package phonon.xc

import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import java.util.logging.Logger
import kotlin.math.max
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Damageable
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector

import com.comphenix.protocol.ProtocolLibrary

import phonon.xc.gun.*
import phonon.xc.utils.mapToObject
import phonon.xc.utils.HitboxSize
import phonon.xc.utils.particle.*
import phonon.xc.utils.debug.DebugTimings
import phonon.xc.utils.blockCrackAnimation.*
import phonon.xc.utils.file.*


/**
 * XC engine global state.
 * Stores all game state and provide XC engine API.
 */
public object XC {
    // spigot plugin variable
    internal var plugin: Plugin? = null
    internal var logger: Logger? = null

    // hooks to other plugins available
    internal var usingProtocolLib: Boolean = false
    internal var usingWorldGuard: Boolean = false

    // namespace keys for custom item properties
    internal var namespaceKeyItemAmmo: NamespacedKey? = null      // key for item ammo value
    internal var namespaceKeyItemReloading: NamespacedKey? = null // key for item is reloading (0 or 1)
    internal var namespaceKeyItemReloadId: NamespacedKey? = null  // key for item reload id
    internal var namespaceKeyItemReloadTimestamp: NamespacedKey? = null  // key for item reload timestamp

    // ========================================================================
    // BUILT-IN ENGINE CONSTANTS
    // ========================================================================
    public const val INVALID_ID: Int = Int.MAX_VALUE       // sentinel value for invalid IDs
    public const val MAX_GUN_CUSTOM_MODEL_ID: Int = 1024   // max allowed gun item custom model id
    public const val MAX_MELEE_CUSTOM_MODEL_ID: Int = 1024 // max allowed melee item custom model id
    public const val MAX_HAT_CUSTOM_MODEL_ID: Int = 1024   // max allowed hat item custom model id
    
    // namespaced keys
    public const val ITEM_KEY_AMMO: String = "ammo"           // ItemStack namespaced key for ammo count
    public const val ITEM_KEY_RELOADING: String = "reloading" // ItemStack namespaced key for gun reloading
    public const val ITEM_KEY_RELOAD_ID: String = "reloadId"  // ItemStack namespaced key for gun reload id
    public const val ITEM_KEY_RELOAD_TIMESTAMP: String = "reloadTime"  // ItemStack namespaced key for gun reload timestamp
    
    // ========================================================================
    // STORAGE
    // ========================================================================
    internal var config: Config = Config()
    
    // gun storage and lookup, 
    internal var guns: Array<Gun?> = Array(MAX_GUN_CUSTOM_MODEL_ID, { _ -> null }) 

    // melee weapon storage and lookup TODO
    internal var melee: Array<Gun?> = Array(MAX_MELEE_CUSTOM_MODEL_ID, { _ -> null })
    
    // custom hat (helmet) storage and lookup TODO
    internal var hats: Array<Gun?> = Array(MAX_HAT_CUSTOM_MODEL_ID, { _ -> null })
    
    // custom hitboxes for armor stand custom models, maps EntityId => HitboxSize
    internal var customModelHitboxes: HashMap<Int, HitboxSize> = HashMap()

    // projectile systems for each world, map world uuid => ProjectileSystem
    internal val projectileSystems: HashMap<UUID, ProjectileSystem> = HashMap()

    // map of players doing automatic firing
    internal val isAutomaticFiring: HashMap<UUID, AutomaticFiring> = HashMap()

    // When gun item reloads, it gets assigned a unique id from this counter.
    // When reload is complete, gun item id is checked with this to make sure
    // player did not swap items during reload that plugin failed to catch.
    internal var gunReloadIdCounter: Int = 0

    // player death message storage, death event checks this for custom messages
    internal val playerDeathMessages: HashMap<UUID, String> = HashMap()

    // queue of player controls requests
    internal var playerShootRequests: ArrayList<PlayerGunShootRequest> = ArrayList()
    internal var playerReloadRequests: ArrayList<PlayerGunReloadRequest> = ArrayList()
    internal var playerUseCustomWeaponRequests: ArrayList<Player> = ArrayList()
    // task finish queues
    internal val playerReloadTaskQueue: LinkedBlockingQueue<PlayerReloadTask> = LinkedBlockingQueue()
    internal val playerReloadCancelledTaskQueue: LinkedBlockingQueue<PlayerReloadCancelledTask> = LinkedBlockingQueue()

    // ========================================================================
    // Async packet queues
    // ========================================================================
    // particle packet spawn queues
    internal var particleBulletTrailQueue: ArrayList<ParticleBulletTrail> = ArrayList()
    internal var particleBulletImpactQueue: ArrayList<ParticleBulletImpact> = ArrayList()

    // block cracking packet animation queue
    internal var blockCrackAnimationQueue: ArrayList<BlockCrackAnimation> = ArrayList()

    // gun ammo message packets
    internal var gunAmmoInfoMessageQueue: ArrayList<AmmoInfoMessagePacket> = ArrayList()

    // ========================================================================
    // Debug/benchmarking
    // ========================================================================
    // For debugging timings
    internal var doDebugTimings: Boolean = true
    internal val debugTimings: DebugTimings = DebugTimings(200) // 200 ticks of timings history
    
    // benchmarking projectiles
    internal var doBenchmark: Boolean = false
    internal var benchmarkProjectileCount: Int = 0
    internal var benchmarkPlayer: Player? = null

    // Built-in guns for debug/benchmark
    internal val gunBenchmarking: Gun = Gun()
    internal var gunDebug: Gun = Gun()

    // ========================================================================
    // RUNNING TASKS
    // ========================================================================
    internal var engineTask: BukkitTask? = null

    /**
     * onEnable:
     * Set links to spigot plugin and logger.
     */
    internal fun onEnable(plugin: Plugin) {
        XC.plugin = plugin
        XC.logger = plugin.getLogger()

        // namespaced keys
        XC.namespaceKeyItemAmmo = NamespacedKey(plugin, ITEM_KEY_AMMO)
        XC.namespaceKeyItemReloading = NamespacedKey(plugin, ITEM_KEY_RELOADING)
        XC.namespaceKeyItemReloadId = NamespacedKey(plugin, ITEM_KEY_RELOAD_ID)
        XC.namespaceKeyItemReloadTimestamp = NamespacedKey(plugin, ITEM_KEY_RELOAD_TIMESTAMP)
    }

    /**
     * Remove hooks to plugins and external APIs
     */
    internal fun onDisable() {
        XC.plugin = null
        XC.logger = null
        XC.namespaceKeyItemAmmo = null
        XC.namespaceKeyItemReloading = null
        XC.namespaceKeyItemReloadId = null
        XC.namespaceKeyItemReloadTimestamp = null
    }

    /**
     * Re-initialize storages and re-load config.
     * TODO: async
     */
    internal fun reload(async: Boolean = false) {
        val timeStart = System.currentTimeMillis()
        XC.cleanup()

        // create projectile systems for each world
        Bukkit.getWorlds().forEach { world ->
            XC.projectileSystems.put(world.getUID(), ProjectileSystem(world))
        }
        
        // reload main plugin config
        val pathConfigToml = Paths.get(XC.plugin!!.getDataFolder().getPath(), "config.toml")
        val config = if ( Files.exists(pathConfigToml) ) {
            Config.fromToml(pathConfigToml, XC.logger)
        } else {
            XC.logger!!.info("Creating default config.toml")
            XC.plugin!!.saveResource("config.toml", false)
            Config()
        }

        XC.config = config
        println(config)

        // load guns
        val filesGuns = listDirFiles(config.pathFilesGun)
        println(filesGuns)
        val gunsLoaded: List<Gun> = filesGuns
            .map { file -> Gun.fromToml(config.pathFilesGun.resolve(file), XC.logger) }
            .filterNotNull()
        
        // map custom model ids => gun (NOTE: guns can overwrite each other!)
        val guns: Array<Gun?> = Array(MAX_GUN_CUSTOM_MODEL_ID, { _ -> null })
        for ( g in gunsLoaded ) {
            // special debug gun
            if ( g.id == -1 ) {
                XC.gunDebug = g
            }

            // map regular guns custom model ids => gun
            val gunModels = arrayOf(
                g.itemModelDefault,
                g.itemModelEmpty,
                g.itemModelReload,
                g.itemModelIronsights,
            )

            for ( modelId in gunModels ) {
                if ( modelId >= 0 ) {
                    if ( modelId < MAX_GUN_CUSTOM_MODEL_ID ) {
                        if ( guns[modelId] != null ) {
                            XC.logger!!.warning("Gun ${g.id} overwrites gun ${guns[modelId]!!.id}")
                        }
                        guns[modelId] = g
                    } else {
                        XC.logger!!.warning("Gun ${g.id} has invalid custom model id: ${modelId}")
                    }
                }
            }
        }

        // temporary: set gun 0 to debug gun
        XC.guns[0] = XC.gunDebug

        // start new engine runnable
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        XC.logger?.info("Reloaded in ${timeLoad}ms")
    }

    /**
     * Function to run after finishing an async scheduled part of a reload.
     */
    internal fun reloadFinishAsync() {
        XC.cleanup()

    }

    /**
     * Cleanup resources before reload or disabling plugin. 
     */
    internal fun cleanup() {
        // clear item definition storages
        XC.guns.fill(null)
        XC.melee.fill(null)
        XC.hats.fill(null)

        // re-create new projectile systems for each world
        XC.projectileSystems.clear()
    }

    /**
     * Starts running engine task
     */
    internal fun start() {
        if ( XC.engineTask == null ) {
            XC.engineTask = Bukkit.getScheduler().runTaskTimer(XC.plugin!!, object: Runnable {
                override fun run() {
                    XC.update()
                }
            }, 0, 0)

            XC.logger!!.info("Starting engine")
        }
        else {
            XC.logger!!.warning("Engine already running")
        }
    }

    /**
     * Stop running engine task
     */
    internal fun stop() {
        val task = XC.engineTask
        if ( task != null ) {
            task.cancel()
            XC.engineTask = null
            XC.logger!!.info("Stopping engine")
        } else {
            XC.logger!!.warning("Engine not running")
        }
    }

    /**
     * Get current reload id counter for reload task.
     */
    internal fun getReloadId(): Int {
        val id = XC.gunReloadIdCounter
        XC.gunReloadIdCounter = max(0, id + 1)
        return id
    }

    /**
     * Wrapper for System.nanoTime(), only runs call
     * if debug timings are on.
     */
    internal fun debugNanoTime(): Long {
        if ( XC.doDebugTimings ) {
            return System.nanoTime()
        } else {
            return 0L
        }
    }

    /**
     * Creates or stops benchmarking task.
     */
    public fun setBenchmark(state: Boolean, numProjectiles: Int = 100, player: Player? = null) {
        if ( state == false ) {
            XC.doBenchmark = false
        }
        else { // begin benchmark task, if player valid
            if ( numProjectiles < 1 || player == null ) {
                XC.logger!!.warning("Invalid benchmark parameters: numProjectiles=${numProjectiles}, player=${player}")
                XC.doBenchmark = false
                return
            }

            XC.doBenchmark = true
            XC.benchmarkProjectileCount = numProjectiles
            XC.benchmarkPlayer = player
        }
    }

    /**
     * Run benchmark task. This should run before projectile system update.
     */
    public fun runBenchmarkProjectiles() {
        if ( XC.doBenchmark == false ) return

        val player = XC.benchmarkPlayer
        if ( player == null ) return
        
        val world = player.world
        val projectileSystem = XC.projectileSystems[world.getUID()]
        if ( projectileSystem == null ) return

        val currNumProjectiles = projectileSystem.size()
        val numToCreate = XC.benchmarkProjectileCount - currNumProjectiles
        if ( numToCreate <= 0 ) return

        val loc = player.location
        val eyeHeight = player.eyeHeight
        val shootPosition = loc.clone().add(0.0, eyeHeight, 0.0)

        val gun = XC.gunDebug
        
        val random = ThreadLocalRandom.current()

        val projectiles = ArrayList<Projectile>(numToCreate)
        for ( _i in 0 until numToCreate ) {

            // randomize shoot direction
            val shootDirX = random.nextDouble(-1.0, 1.0)
            val shootDirZ = random.nextDouble(-1.0, 1.0)
            val shootDirY = random.nextDouble(-0.1, 0.5)

            projectiles.add(Projectile(
                gun = gun,
                source = player,
                x = shootPosition.x.toFloat(),
                y = shootPosition.y.toFloat(),
                z = shootPosition.z.toFloat(),
                dirX = shootDirX.toFloat(),
                dirY = shootDirY.toFloat(),
                dirZ = shootDirZ.toFloat(),
                speed = gun.projectileVelocity,
                gravity = gun.projectileGravity,
                maxLifetime = gun.projectileLifetime,
                maxDistance = gun.projectileMaxDistance,
            ))
        }
        projectileSystem.addProjectiles(projectiles)
    }

    
    /**
     * Main engine update, runs on each tick
     */
    internal fun update() {
        val tUpdateStart = XC.debugNanoTime() // timing probe

        XC.debugTimings.tick()
        XC.runBenchmarkProjectiles() // debugging

        val tShootSystem = XC.debugNanoTime() // timing probe
        // run player gun controls systems
        gunPlayerShootSystem(XC.playerShootRequests)
        gunPlayerReloadSystem(XC.playerReloadRequests)
        XC.playerShootRequests = ArrayList()
        XC.playerReloadRequests = ArrayList()
        XC.playerUseCustomWeaponRequests = ArrayList()

        
        // finish gun reloading tasks
        val tReloadSystem = XC.debugNanoTime() // timing probe
        val gunReloadTasks = ArrayList<PlayerReloadTask>()
        val gunReloadCancelledTasks = ArrayList<PlayerReloadCancelledTask>()
        XC.playerReloadTaskQueue.drainTo(gunReloadTasks)
        XC.playerReloadCancelledTaskQueue.drainTo(gunReloadCancelledTasks)
        doGunReload(gunReloadTasks)
        doGunReloadCancelled(gunReloadCancelledTasks)

        val tProjectileSystem = XC.debugNanoTime() // timing probe

        // update projectile systems for each world
        for ( projSys in this.projectileSystems.values ) {
            val (hitboxes, hitBlocksQueue, hitEntitiesQueue) = projSys.update()
            
            // handle hit blocks and entities
            // if ( hitBlocksQueue.size > 0 ) println("HIT BLOCKS: ${hitBlocksQueue}")
            // if ( hitEntitiesQueue.size > 0 ) println("HIT ENTITIES: ${hitEntitiesQueue}")

            for ( hitBlock in hitBlocksQueue ) {
                hitBlock.gun.hitBlockHandler(hitBlock.gun, hitBlock.location, hitBlock.block, hitBlock.source)
            }

            for ( hitEntity in hitEntitiesQueue ) {
                hitEntity.gun.hitEntityHandler(hitEntity.gun, hitEntity.location, hitEntity.entity, hitEntity.source)
            }

            // TODO: handle explosions queue here
        }

        // ================================================
        // SCHEDULE ALL ASYNC TASKS (particles, packets)
        // ================================================
        val tStartPackets = XC.debugNanoTime() // timing probe

        val particleBulletTrails = XC.particleBulletTrailQueue
        val particleBulletImpacts = XC.particleBulletImpactQueue
        val gunAmmoInfoMessages = XC.gunAmmoInfoMessageQueue

        XC.particleBulletTrailQueue = ArrayList()
        XC.particleBulletImpactQueue = ArrayList()
        XC.gunAmmoInfoMessageQueue = ArrayList()

        Bukkit.getScheduler().runTaskAsynchronously(
            XC.plugin!!,
            TaskSpawnParticleBulletTrails(particleBulletTrails),
        )
        Bukkit.getScheduler().runTaskAsynchronously(
            XC.plugin!!,
            TaskSpawnParticleBulletImpacts(particleBulletImpacts),
        )
        Bukkit.getScheduler().runTaskAsynchronously(
            XC.plugin!!,
            TaskAmmoInfoMessages(gunAmmoInfoMessages),
        )

        // sync
        // TaskSpawnParticleBulletTrails(particleBulletTrails).run()
        // TaskSpawnParticleBulletImpacts(particleBulletImpacts).run()
        // TaskAmmoInfoMessages(gunAmmoInfoMessages).run()

        // custom packets (only if ProtocolLib is available)
        if ( XC.usingProtocolLib ) {
            // block crack animations
            val blockCrackAnimations = XC.blockCrackAnimationQueue
            XC.blockCrackAnimationQueue = ArrayList()
            Bukkit.getScheduler().runTaskAsynchronously(
                XC.plugin!!,
                TaskBroadcastBlockCrackAnimations(ProtocolLibrary.getProtocolManager(), blockCrackAnimations),
            )

            // player recoil from gun firing

            // sync
            // TaskBroadcastBlockCrackAnimations(ProtocolLibrary.getProtocolManager(), blockCrackAnimations).run()
        }
        
        // timings
        if ( XC.doDebugTimings ) {
            val tEndPackets = XC.debugNanoTime()
            val tUpdateEnd = XC.debugNanoTime()

            XC.debugTimings.add("shoot", tReloadSystem - tShootSystem)
            XC.debugTimings.add("reload", tProjectileSystem - tReloadSystem)
            XC.debugTimings.add("packets", tEndPackets - tStartPackets)
            XC.debugTimings.add("total", tUpdateEnd - tUpdateStart)
        }
    }
}