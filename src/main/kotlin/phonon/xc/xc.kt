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
import kotlin.math.floor
import kotlin.math.ceil
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.Particle
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Damageable
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

import com.comphenix.protocol.ProtocolLibrary

// i hate java
import phonon.xc.ammo.*
import phonon.xc.armor.*
import phonon.xc.gun.*
import phonon.xc.gun.crawl.*
import phonon.xc.landmine.*
import phonon.xc.melee.*
import phonon.xc.throwable.*
import phonon.xc.util.EnumArrayMap
import phonon.xc.util.mapToObject
import phonon.xc.util.Hitbox
import phonon.xc.util.HitboxSize
import phonon.xc.util.particle.*
import phonon.xc.util.recoil.*
import phonon.xc.util.sound.*
import phonon.xc.util.debug.DebugTimings
import phonon.xc.util.blockCrackAnimation.*
import phonon.xc.util.file.listDirFiles
import phonon.xc.util.WorldGuard
import phonon.xc.util.death.*

// TODO: in future need to select NMS version
import phonon.xc.nms.gun.crawl.*
import phonon.xc.nms.gun.item.*


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
    internal var namespaceKeyItemBurstFireId: NamespacedKey? = null  // key for item burst firing id
    internal var namespaceKeyItemAutoFireId: NamespacedKey? = null  // key for item auto firing id
    internal var namespaceKeyItemCrawlToShootId: NamespacedKey? = null  // key for item crawl to shoot id
    internal var namespaceKeyItemThrowableId: NamespacedKey? = null  // key for item throwable id

    internal var nbtKeyItemAmmo: String = ""            // raw nbt key string for item ammo value
    internal var nbtKeyItemReloading: String = ""       // raw nbt key string for item is reloading (0 or 1)
    internal var nbtKeyItemReloadId: String = ""        // raw nbt key string for item reload id
    internal var nbtKeyItemReloadTimestamp: String = "" // raw nbt key string for item reload timestamp
    internal var nbtKeyItemBurstFireId: String = ""     // raw nbt key for item burst firing id
    internal var nbtKeyItemAutoFireId: String = ""      // raw nbt key for item auto firing id
    internal var nbtKeyItemCrawlToShootId: String = ""  // raw nbt key for crawl to shoot id
    internal var nbtKeyItemThrowableId: String = ""     // raw nbt key for throwable id

    // ========================================================================
    // BUILT-IN ENGINE CONSTANTS
    // ========================================================================
    public const val INVALID_ID: Int = Int.MAX_VALUE       // sentinel value for invalid IDs
    public const val MAX_AMMO_CUSTOM_MODEL_ID: Int = 1024  // max allowed ammo item custom model id
    public const val MAX_GUN_CUSTOM_MODEL_ID: Int = 1024   // max allowed gun item custom model id
    public const val MAX_MELEE_CUSTOM_MODEL_ID: Int = 1024 // max allowed melee item custom model id
    public const val MAX_HAT_CUSTOM_MODEL_ID: Int = 1024   // max allowed hat item custom model id
    public const val MAX_THROWABLE_CUSTOM_MODEL_ID: Int = 1024   // max allowed hat item custom model id
    
    // item types (using int const instead of enum)
    public const val ITEM_TYPE_INVALID: Int = -1
    public const val ITEM_TYPE_AMMO: Int = 0
    public const val ITEM_TYPE_GUN: Int = 1
    public const val ITEM_TYPE_MELEE: Int = 2
    public const val ITEM_TYPE_THROWABLE: Int = 3
    public const val ITEM_TYPE_HAT: Int = 4
    public const val ITEM_TYPE_LANDMINE: Int = 5

    // namespaced keys
    public const val ITEM_KEY_AMMO: String = "ammo"           // ItemStack namespaced key for ammo count
    public const val ITEM_KEY_RELOADING: String = "reloading" // ItemStack namespaced key for gun reloading
    public const val ITEM_KEY_RELOAD_ID: String = "reloadId"  // ItemStack namespaced key for gun reload id
    public const val ITEM_KEY_RELOAD_TIMESTAMP: String = "reloadTime"  // ItemStack namespaced key for gun reload timestamp
    public const val ITEM_KEY_BURST_FIRE_ID: String = "burstId"  // ItemStack namespaced key for gun burst fire id
    public const val ITEM_KEY_AUTO_FIRE_ID: String = "autoId"  // ItemStack namespaced key for gun auto fire id
    public const val ITEM_KEY_CRAWL_TO_SHOOT_ID: String = "crawlId"  // ItemStack namespaced key for gun crawl to shoot request id
    public const val ITEM_KEY_THROWABLE_ID: String = "throwId"  // ItemStack namespaced key for a ready throwable item
    
    // ========================================================================
    // STORAGE
    // ========================================================================
    internal var config: Config = Config()
    
    // gun storage and lookup
    internal var guns: Array<Gun?> = Array(MAX_GUN_CUSTOM_MODEL_ID, { _ -> null }) 
    internal var gunIds: IntArray = intArrayOf() // cached non null gun ids

    // melee weapon storage and lookup
    internal var melee: Array<MeleeWeapon?> = Array(MAX_MELEE_CUSTOM_MODEL_ID, { _ -> null })
    internal var meleeIds: IntArray = intArrayOf() // cached non null melee ids

    // custom hat (helmet) storage and lookup
    internal var hats: Array<Hat?> = Array(MAX_HAT_CUSTOM_MODEL_ID, { _ -> null })
    internal var hatIds: IntArray = intArrayOf() // cached non null hat ids

    // melee weapon storage and lookup
    internal var throwable: Array<ThrowableItem?> = Array(MAX_THROWABLE_CUSTOM_MODEL_ID, { _ -> null })
    internal var throwableIds: IntArray = intArrayOf() // cached non null throwing item ids

    // ammo lookup
    internal var ammo: Array<Ammo?> = Array(MAX_AMMO_CUSTOM_MODEL_ID, { _ -> null })
    internal var ammoIds: IntArray = intArrayOf() // cached non null ammo Ids

    // landmine storage: material => landmine properties lookup
    internal var landmines: EnumMap<Material, Landmine> = EnumMap(Material::class.java)

    // custom hitboxes for armor stand custom models, maps EntityId => HitboxSize
    internal var customModelHitboxes: HashMap<UUID, HitboxSize> = HashMap()

    // projectile systems for each world, map world uuid => ProjectileSystem
    internal val projectileSystems: HashMap<UUID, ProjectileSystem> = HashMap(4) // initial capacity 4 worlds

    // map of players and aim down sights settings
    internal val dontUseAimDownSights: HashSet<UUID> = HashSet()
    
    // map of player => gun shoot delays
    internal val playerShootDelay: HashMap<UUID, ShootDelay> = HashMap()

    // map of players => recoil multiplier
    internal var playerRecoil: HashMap<UUID, Double> = HashMap()
    
    // map of players => speed for sway multiplier (in blocks/tick)
    internal var playerSpeed: HashMap<UUID, Double> = HashMap() 

    // map of players => previous location
    internal var playerPreviousLocation: HashMap<UUID, Location> = HashMap()
    
    // map of players => custom death messages
    internal val deathEvents: HashMap<UUID, XcPlayerDeathEvent> = HashMap()

    // list of saved death events for statistics
    internal var playerDeathRecords: ArrayList<PlayerDeathRecord> = ArrayList()

    // counter for running player death record saving
    internal var playerDeathRecordSaveCounter: Int = 0

    // When gun item reloads, it gets assigned a unique id from this counter.
    // When reload is complete, gun item id is checked with this to make sure
    // player did not swap items during reload that plugin failed to catch.
    // Using int instead of atomic int since mineman is single threaded.
    internal var gunReloadIdCounter: Int = 0
    
    // Burst and auto fire ID counter. Used to detect if player is firing
    // the same weapon in a burst or auto fire sequence.
    // Using int instead of atomic int since mineman is single threaded.
    internal var burstFireIdCounter: Int = 0
    internal var autoFireIdCounter: Int = 0

    // ID counter for crawl to shoot request.
    internal var crawlToShootIdCounter: Int = 0

    // id counters for crawl refresh "load balancing"
    // hard coded for only 2 ticks
    internal var crawlRefreshTick0Count: Int = 0
    internal var crawlRefreshTick1Count: Int = 0

    // id counter for throwable items (when they are readied)
    internal var throwableIdCounter: Int = 0

    // player death message storage, death event checks this for custom messages
    internal val playerDeathMessages: HashMap<UUID, String> = HashMap()

    // queue of player controls requests
    // Note: do reload before shoot. Shoot request when ammo <= 0 queue
    // a new reload request, which should be handled next tick
    internal var playerAimDownSightsRequests: ArrayList<PlayerAimDownSightsRequest> = ArrayList()
    internal var playerGunSelectRequests: ArrayList<PlayerGunSelectRequest> = ArrayList()
    internal var playerReloadRequests: ArrayList<PlayerGunReloadRequest> = ArrayList()
    internal var playerShootRequests: ArrayList<PlayerGunShootRequest> = ArrayList()
    internal var playerAutoFireRequests: ArrayList<PlayerAutoFireRequest> = ArrayList()
    internal var playerGunCleanupRequests: ArrayList<PlayerGunCleanupRequest> = ArrayList()
    internal var itemGunCleanupRequests: ArrayList<ItemGunCleanupRequest> = ArrayList()
    // burst firing queue: map entity uuid -> burst fire state
    internal var burstFiringPackets: HashMap<UUID, BurstFire> = HashMap()
    // automatic firing queue: map entity uuid -> automatic fire state
    internal var autoFiringPackets: HashMap<UUID, AutoFire> = HashMap()
    // crawling system queues
    internal var crawlRequestTasks: HashMap<UUID, CrawlToShootRequestTask> = HashMap()
    internal var crawlStartQueue: ArrayList<CrawlStart> = ArrayList()
    internal var crawlStopQueue: ArrayList<CrawlStop> = ArrayList()
    internal var crawling: HashMap<UUID, Crawling> = HashMap()
    internal var crawlToShootRequestQueue: ArrayList<CrawlToShootRequest> = ArrayList()
    internal val crawlingAndReadyToShoot: HashMap<UUID, Boolean> = HashMap() // map of players => is crawling and ready to shoot
    // throwable systems
    internal var readyThrowableRequests: ArrayList<ReadyThrowableRequest> = ArrayList()
    internal var throwThrowableRequests: ArrayList<ThrowThrowableRequest> = ArrayList()
    internal var droppedThrowables: ArrayList<DroppedThrowable> = ArrayList()
    internal var readyThrowables: HashMap<Int, ReadyThrowable> = HashMap()
    // per-world throwables queues
    internal var expiredThrowables: HashMap<UUID, ArrayList<ExpiredThrowable>> = HashMap()
    internal var thrownThrowables: HashMap<UUID, ArrayList<ThrownThrowable>> = HashMap()
    // landmine systems
    internal var landmineActivationRequests: ArrayList<LandmineActivationRequest> = ArrayList()
    internal var landmineFinishUseRequests: ArrayList<LandmineFinishUseRequest> = ArrayList()
    internal var landmineExplosions: HashMap<UUID, ArrayList<LandmineExplosionRequest>> = HashMap() // per world landmine explosion queues
    // task finish queues
    internal val playerReloadTaskQueue: LinkedBlockingQueue<PlayerReloadTask> = LinkedBlockingQueue()
    internal val playerReloadCancelledTaskQueue: LinkedBlockingQueue<PlayerReloadCancelledTask> = LinkedBlockingQueue()
    internal val playerCrawlRequestFinishQueue: LinkedBlockingQueue<CrawlToShootRequestFinish> = LinkedBlockingQueue()
    internal val playerCrawlRequestCancelQueue: LinkedBlockingQueue<CrawlToShootRequestCancel> = LinkedBlockingQueue()

    // hats
    internal var wearHatRequests: ArrayList<PlayerWearHatRequest> = ArrayList()

    // ========================================================================
    // Async packet queues
    // ========================================================================
    // particle packet spawn queues
    internal var particleBulletTrailQueue: ArrayList<ParticleBulletTrail> = ArrayList()
    internal var particleBulletImpactQueue: ArrayList<ParticleBulletImpact> = ArrayList()
    internal var particleExplosionQueue: ArrayList<ParticleExplosion> = ArrayList()

    // block cracking packet animation queue
    internal var blockCrackAnimationQueue: ArrayList<BlockCrackAnimation> = ArrayList()

    // gun ammo message packets
    internal var gunAmmoInfoMessageQueue: ArrayList<AmmoInfoMessagePacket> = ArrayList()

    // sounds queue
    internal var soundQueue: ArrayList<SoundPacket> = ArrayList()

    // recoil packets
    internal var recoilQueue: ArrayList<RecoilPacket> = ArrayList()

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
        XC.namespaceKeyItemBurstFireId = NamespacedKey(plugin, ITEM_KEY_BURST_FIRE_ID)
        XC.namespaceKeyItemAutoFireId = NamespacedKey(plugin, ITEM_KEY_AUTO_FIRE_ID)
        XC.namespaceKeyItemCrawlToShootId = NamespacedKey(plugin, ITEM_KEY_CRAWL_TO_SHOOT_ID)
        XC.namespaceKeyItemThrowableId = NamespacedKey(plugin, ITEM_KEY_THROWABLE_ID)
        
        // raw nbt keys
        XC.nbtKeyItemAmmo = XC.namespaceKeyItemAmmo!!.toString()
        XC.nbtKeyItemReloading = XC.namespaceKeyItemReloading!!.toString()
        XC.nbtKeyItemReloadId = XC.namespaceKeyItemReloadId!!.toString()
        XC.nbtKeyItemReloadTimestamp = XC.namespaceKeyItemReloadTimestamp!!.toString()
        XC.nbtKeyItemBurstFireId = XC.namespaceKeyItemBurstFireId!!.toString()
        XC.nbtKeyItemAutoFireId = XC.namespaceKeyItemAutoFireId!!.toString()
        XC.nbtKeyItemCrawlToShootId =XC.namespaceKeyItemCrawlToShootId!!.toString()
        XC.nbtKeyItemThrowableId = XC.namespaceKeyItemThrowableId!!.toString()

        // reset counters
        XC.crawlRefreshTick0Count = 0
        XC.crawlRefreshTick1Count = 0

        // reset queues
        // TODO
    }

    /**
     * Remove hooks to plugins and external APIs
     */
    internal fun onDisable() {
        // cleanup crawl fake entity/packets
        for ( (_playerId, crawlState) in crawling ) {
            crawlState.cleanup()
        }

        // flush death stats save
        TaskSavePlayerDeathRecords(XC.playerDeathRecords, XC.config.playerDeathLogSaveDir).run()
        
        XC.plugin = null
        XC.logger = null
        XC.namespaceKeyItemAmmo = null
        XC.namespaceKeyItemReloading = null
        XC.namespaceKeyItemReloadId = null
        XC.namespaceKeyItemReloadTimestamp = null
        XC.namespaceKeyItemBurstFireId = null
        XC.namespaceKeyItemAutoFireId = null
        XC.namespaceKeyItemCrawlToShootId = null
    }

    /**
     * Re-initialize storages and re-load config.
     * TODO: async
     */
    internal fun reload(async: Boolean = false) {
        val timeStart = System.currentTimeMillis()
        XC.cleanup()

        // create projectile and throwable systems for each world
        Bukkit.getWorlds().forEach { world ->
            XC.projectileSystems.put(world.getUID(), ProjectileSystem(world))
            XC.thrownThrowables.put(world.getUID(), ArrayList())
            XC.expiredThrowables.put(world.getUID(), ArrayList())
            XC.landmineExplosions.put(world.getUID(), ArrayList())
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
        XC.doDebugTimings = config.defaultDoDebugTimings

        // load from toml config files

        val filesGuns = listDirFiles(config.pathFilesGun)
        val gunsLoaded: List<Gun> = filesGuns
            .map { file -> Gun.fromToml(config.pathFilesGun.resolve(file), XC.logger) }
            .filterNotNull()

        val filesAmmo = listDirFiles(config.pathFilesAmmo)
        val ammoLoaded: List<Ammo> = filesAmmo
            .map { file -> Ammo.fromToml(config.pathFilesAmmo.resolve(file), XC.logger) }
            .filterNotNull()

        val filesMelee = listDirFiles(config.pathFilesMelee)
        val meleeLoaded: List<MeleeWeapon> = filesMelee
            .map { file -> MeleeWeapon.fromToml(config.pathFilesMelee.resolve(file), XC.logger) }
            .filterNotNull()
        
        val filesThrowable = listDirFiles(config.pathFilesThrowable)
        val throwableLoaded: List<ThrowableItem> = filesThrowable
            .map { file -> ThrowableItem.fromToml(config.pathFilesThrowable.resolve(file), XC.logger) }
            .filterNotNull()
        
        val filesHats = listDirFiles(config.pathFilesArmor)
        val hatsLoaded: List<Hat> = filesHats
            .map { file -> Hat.listFromToml(config.pathFilesArmor.resolve(file), XC.logger) }
            .filterNotNull()
            .flatten() // this flattens a List<List<Hat>> -> List<Hat>
        
        val filesLandmine = listDirFiles(config.pathFilesLandmine)
        val landminesLoaded: List<Landmine> = filesLandmine
            .map { file -> Landmine.fromToml(config.pathFilesLandmine.resolve(file), XC.logger) }
            .filterNotNull()
        
        // map custom model ids => gun (NOTE: guns can overwrite each other!)
        val guns: Array<Gun?> = Array(MAX_GUN_CUSTOM_MODEL_ID, { _ -> null })
        val validGunIds = mutableSetOf<Int>()
        for ( item in gunsLoaded ) {
            // special debug gun
            if ( item.id == -1 ) {
                XC.gunDebug = item
            }

            // add default gun model id to validGunIds (this overwrites duplicates)
            validGunIds.add(item.itemModelDefault)

            // map regular guns custom model ids => gun
            val gunModels = arrayOf(
                item.itemModelDefault,
                item.itemModelEmpty,
                item.itemModelReload,
                item.itemModelAimDownSights,
            )

            for ( modelId in gunModels ) {
                if ( modelId >= 0 ) {
                    if ( modelId < MAX_GUN_CUSTOM_MODEL_ID ) {
                        if ( guns[modelId] != null ) {
                            XC.logger!!.warning("Gun ${item.itemName} (${item.id}) overwrites gun ${guns[modelId]!!.id}")
                        }
                        guns[modelId] = item
                    } else {
                        XC.logger!!.warning("Gun ${item.id} has invalid custom model id: ${modelId} (max allowed is ${MAX_GUN_CUSTOM_MODEL_ID})")
                    }
                }
            }
        }

        // temporary: set gun 0 to debug gun
        guns[0] = XC.gunDebug

        // map melee ids => melee weapon
        val melee: Array<MeleeWeapon?> = Array(MAX_MELEE_CUSTOM_MODEL_ID, { _ -> null })
        val validMeleeIds = mutableSetOf<Int>()
        for ( item in meleeLoaded ) {
            // map all custom model ids => item
            val models = arrayOf(
                item.itemModelDefault,
            )

            validMeleeIds.add(item.itemModelDefault)

            for ( modelId in models ) {
                if ( modelId >= 0 ) {
                    if ( modelId < MAX_MELEE_CUSTOM_MODEL_ID ) {
                        if ( melee[modelId] != null ) {
                            XC.logger!!.warning("Melee weapon ${item.itemName} overwrites ${melee[modelId]!!.itemModelDefault}")
                        }
                        melee[modelId] = item
                    } else {
                        XC.logger!!.warning("Melee weapon ${item.itemName} has invalid custom model id: ${modelId} (max allowed is ${MAX_MELEE_CUSTOM_MODEL_ID})")
                    }
                }
            }
        }

        // map throwable ids => throwable weapon
        val throwable: Array<ThrowableItem?> = Array(MAX_THROWABLE_CUSTOM_MODEL_ID, { _ -> null })
        val validThrowableIds = mutableSetOf<Int>()
        for ( item in throwableLoaded ) {
            // map all custom model ids => item
            val models = arrayOf(
                item.itemModelDefault,
                item.itemModelReady,
            )

            validThrowableIds.add(item.itemModelDefault)

            for ( modelId in models ) {
                if ( modelId >= 0 ) {
                    if ( modelId < MAX_THROWABLE_CUSTOM_MODEL_ID ) {
                        if ( throwable[modelId] != null ) {
                            XC.logger!!.warning("Throwable ${item.itemName} overwrites ${throwable[modelId]!!.itemModelDefault}")
                        }
                        throwable[modelId] = item
                    } else {
                        XC.logger!!.warning("Throwable ${item.itemName} has invalid custom model id: ${modelId} (max allowed is ${MAX_THROWABLE_CUSTOM_MODEL_ID})")
                    }
                }
            }
        }
        // map ammo id => ammo
        val ammo: Array<Ammo?> = Array(XC.MAX_AMMO_CUSTOM_MODEL_ID, { _ -> null })
        val validAmmoIds = mutableSetOf<Int>()
        for ( a in ammoLoaded ) {
            if ( a.id < XC.MAX_AMMO_CUSTOM_MODEL_ID ) {
                ammo[a.id] = a
                validAmmoIds.add(a.id)
            } else {
                XC.logger!!.warning("Ammo ${a.itemName} has invalid custom model id: ${a.id} (max allowed = ${XC.MAX_AMMO_CUSTOM_MODEL_ID})")
            }
        }
        
        // map hat id => hat
        val hats: Array<Hat?> = Array(MAX_HAT_CUSTOM_MODEL_ID, { _ -> null })
        val validHatIds = mutableSetOf<Int>()
        for ( h in hatsLoaded ) {
            hats[h.itemModel] = h
            validHatIds.add(h.itemModel)
        }

        // add landmines to enum map
        val landmines: EnumMap<Material, Landmine> = EnumMap(Material::class.java)
        for ( l in landminesLoaded ) {
            if ( l.material in landmines ) {
                XC.logger!!.warning("Landmine ${l.itemName} overwrites ${landmines[l.material]!!.itemName}")
            }
            landmines[l.material] = l
        }

        // set guns/ammos/etc...
        XC.ammo = ammo
        XC.ammoIds = validAmmoIds.toIntArray().sortedArray()
        XC.guns = guns
        XC.gunIds = validGunIds.toIntArray().sortedArray()
        XC.hats = hats
        XC.hatIds = validHatIds.toIntArray().sortedArray()
        XC.melee = melee
        XC.meleeIds = validMeleeIds.toIntArray().sortedArray()
        XC.throwable = throwable
        XC.throwableIds = validThrowableIds.toIntArray().sortedArray()
        XC.landmines = landmines

        // start new engine runnable
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        XC.logger?.info("Reloaded in ${timeLoad}ms")
        XC.logger?.info("- Guns: ${validGunIds.size}")
        XC.logger?.info("- Ammo: ${validAmmoIds.size}")
        XC.logger?.info("- Melee: ${validMeleeIds.size}")
        XC.logger?.info("- Throwable: ${validThrowableIds.size}")
        XC.logger?.info("- Hats: ${validHatIds.size}")
        XC.logger?.info("- Landmines: ${landmines.size}")
    }

    /**
     * Function to run after finishing an async scheduled part of a reload.
     */
    internal fun reloadFinishAsync() {
        XC.cleanup()
        // TODO
    }

    /**
     * Cleanup resources before reload or disabling plugin. 
     */
    internal fun cleanup() {
        // clear item definition storages
        XC.guns.fill(null)
        XC.melee.fill(null)
        XC.hats.fill(null)
        
        // clear death message and stats tracking
        TaskSavePlayerDeathRecords(XC.playerDeathRecords, XC.config.playerDeathLogSaveDir).run()
        XC.playerDeathRecords = ArrayList()
        XC.deathEvents.clear()

        // re-create new projectile systems for each world
        XC.projectileSystems.clear()
        XC.thrownThrowables.clear()
        XC.expiredThrowables.clear()
        XC.landmineExplosions.clear()
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
    internal fun newReloadId(): Int {
        val id = XC.gunReloadIdCounter
        XC.gunReloadIdCounter = max(0, id + 1)
        return id
    }

    /**
     * Get current burst id counter for burst firing.
     * Used to detect if same gun is being fired in burst mode.
     */
    internal fun newBurstFireId(): Int {
        val id = XC.burstFireIdCounter
        XC.burstFireIdCounter = max(0, id + 1)
        return id
    }

    /**
     * Get current auto fire id counter for auto firing
     * Used to detect if same gun is being fired in automatic mode.
     */
    internal fun newAutoFireId(): Int {
        val id = XC.autoFireIdCounter
        XC.autoFireIdCounter = max(0, id + 1)
        return id
    }

    /**
     * Get current crawl to shoot id counter for crawling.
     */
    internal fun newCrawlToShootId(): Int {
        val id = XC.crawlToShootIdCounter
        XC.crawlToShootIdCounter = max(0, id + 1)
        return id
    }

    /**
     * Assign a crawl refresh id.
     * E.g. for two allowed tick assignments,
     * each refresh system tick counts 0..1..0..1..0..
     * If the `crawling.id == refresh tick id`, then refresh
     * will run. Assign each crawling id to the refresh tick
     * that currently has the LEAST number of crawling ids.
     */
    internal fun newCrawlRefreshId(): Int {
        if ( XC.crawlRefreshTick0Count < XC.crawlRefreshTick1Count ) {
            return 0
        } else {
            return 1
        }
    }

    /**
     * Frees crawl refresh id.
     * Decrement the index for this tick id.
     */
    internal fun freeCrawlRefreshId(index: Int) {
        if ( index == 0 ) {
            XC.crawlRefreshTick0Count = max(0, XC.crawlRefreshTick0Count - 1)
        } else {
            XC.crawlRefreshTick1Count = max(0, XC.crawlRefreshTick1Count - 1)
        }
    }

    /**
     * Create new throwable id from global counter.
     * 
     * Note: throwable ids are used inside the map
     *      XC.readyThrowable[throwId] -> ReadyThrowable
     * It's technically possible for this to overflow and overwrite.
     * But extremely unlikely, since throwables have a lifetime and
     * should be removed from this map before 
     * Integer.MAX_VALUE new throwables are created to overflow and
     * overwrite the key.
     */
    internal fun newThrowableId(): Int {
        val id = XC.throwableIdCounter
        XC.throwableIdCounter = max(0, id + 1)
        return id
    }

    /**
     * Protection check if location allows player pvp damage.
     * In future, this should add other hooked plugin checks.
     */
    internal fun canPvpAt(loc: Location): Boolean {
        if ( XC.usingWorldGuard ) {
            return WorldGuard.canPvpAt(loc)
        }

        return true
    }

    /**
     * Protection check if location allows explosions.
     * This includes all explosion behavior (custom damage, particles,
     * and block damage).
     * In future, this should add other hooked plugin checks.
     */
    internal fun canExplodeAt(loc: Location): Boolean {
        if ( XC.usingWorldGuard ) {
            return WorldGuard.canExplodeAt(loc)
        }

        return true
    }

    /**
     * Protection check if location allows fire.
     */
    internal fun canCreateFireAt(loc: Location): Boolean {
        if ( XC.usingWorldGuard ) {
            return WorldGuard.canCreateFireAt(loc)
        }

        return true
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
     * Return current XC config.
     */
    public fun config(): Config {
        return XC.config
    }

    /**
     * Return Ammo object for given id if it exists.
     */
    public fun getAmmo(id: Int): Ammo? {
        return XC.ammo[id]
    }

    /**
     * Map an uuid to a custom hitbox size. UUID flexible, can be
     * entity unique id, or uuid managed by other systems.
     */
    public fun addHitbox(uuid: UUID, hitbox: HitboxSize) {
        XC.customModelHitboxes[uuid] = hitbox
    }

    /**
     * Remove custom hitbox from uuid if it exists.
     */
    public fun removeHitbox(uuid: UUID) {
        XC.customModelHitboxes.remove(uuid)
    }

    /**
     * Adds projectile to projectile system if it exists.
     */
    public fun addProjectile(world: World, projectile: Projectile) {
        XC.projectileSystems[world.getUID()]?.let { sys ->
            sys.addProjectile(projectile)
        }
    }

    /**
     * Add player hitbox debug request
     */
    public fun debugHitboxRequest(player: Player, range: Int) {
        val world = player.world
        val loc = player.location

        val cxmin = (floor(loc.x).toInt() shr 4) - range
        val cxmax = (ceil(loc.x).toInt() shr 4) + range
        val czmin = (floor(loc.z).toInt() shr 4) - range
        val czmax = (ceil(loc.z).toInt() shr 4) + range
        
        for ( cx in cxmin..cxmax ) {
            for ( cz in czmin..czmax ) {
                if ( world.isChunkLoaded(cx, cz) ) {
                    val chunk = world.getChunkAt(cx, cz)

                    for ( entity in chunk.getEntities() ) {
                        // special handling for custom model hitboxes
                        if ( entity.type == EntityType.ARMOR_STAND ) {
                            val hitboxSize = XC.customModelHitboxes.get(entity.getUniqueId())
                            if ( hitboxSize != null ) {
                                Hitbox.from(entity, hitboxSize).visualize(world, Particle.VILLAGER_HAPPY)
                                continue
                            }
                        }
        
                        // regular entities
                        if ( XC.config.entityTargetable[entity.type] ) {
                            Hitbox.from(entity, XC.config.entityHitboxSizes[entity.type]).visualize(world, Particle.VILLAGER_HAPPY)
                        }
                    }
                }
            }
        }
    }

    /**
     * Set a player's aim down sights setting.
     */
    public fun setAimDownSights(player: Player, use: Boolean) {
        if ( use ) {
            XC.dontUseAimDownSights.remove(player.getUniqueId())
        } else {
            XC.dontUseAimDownSights.add(player.getUniqueId())
        }
    }
    
    /**
     * This adds an aim down sights model to the player's offhand,
     * for aim down sights visual.
     * 
     * TODO: USE SET_SLOT PACKET INSTEAD OF A REAL ITEM!!!
     */
    internal fun createAimDownSightsOffhandModel(gun: Gun, player: Player) {
        // println("createAimDownSightsOffhandModel")
        val equipment = player.getInventory()
        
        // drop current offhand item
        val itemOffhand = equipment.getItemInOffHand()
        if ( itemOffhand != null && itemOffhand.type != Material.AIR ) {
            // drop offhand item in world and remove item in offhand
            player.getWorld().dropItem(player.getLocation(), itemOffhand)
            equipment.setItemInOffHand(null)
            
            // DEPRECATED
            // if this is an existing aim down sights model, ignore (ADS models can be glitchy)
            // val itemMeta = itemOffhand.getItemMeta()
            // if ( itemOffhand.type != XC.config.materialAimDownSights || ( itemMeta.hasCustomModelData() && itemMeta.getCustomModelData() >= XC.MAX_GUN_CUSTOM_MODEL_ID ) ) {
            //     player.getWorld().dropItem(player.getLocation(), itemOffhand)
            // }
        }

        // create new offhand item
        gun.aimDownSightsModel.create(player)
    }

    /**
     * Removes any aim down sights custom model in player's offhand.
     */
    internal fun removeAimDownSightsOffhandModel(player: Player) {
        // println("removeAimDownSightsOffhandModel")
        val equipment = player.getInventory()
        
        // remove offhand item if its an aim down sights model
        val itemOffhand = equipment.getItemInOffHand()
        if ( itemOffhand == null || itemOffhand.type == Material.AIR ) { // no real item here, send packet removing fake ads item
            AimDownSightsModel.destroy(player)
        }
    }

    /**
     * Create aim down sights model using a real item.
     * Deprecated because using pure packets to make a fake item.
     * No real item is created in offhand, so this is unnecessary.
     */
    @Deprecated(message = "Use PacketPlayOutSetSlot instead of a real ItemStack")
    private fun createAimDownSightsOffhandModelItemStack(gun: Gun): ItemStack {
        val item = ItemStack(XC.config.materialAimDownSights, 1)
        val itemMeta = item.getItemMeta()

        itemMeta.setDisplayName("Aim down sights")
        itemMeta.setCustomModelData(gun.itemModelAimDownSights)

        item.setItemMeta(itemMeta)

        return item
    }
    

    /**
     * Remove aim down sights item stack in offhand.
     * Deprecated because using pure packets, no real item is created.
     */
    @Deprecated(message = "Use PacketPlayOutSetSlot instead of a real ItemStack")
    private fun removeAimDownSightsOffhandModelItemStack(player: Player) {
        // println("removeAimDownSightsOffhandModelItemStack")
        val equipment = player.getInventory()
        val itemOffhand = equipment.getItemInOffHand()
        if ( itemOffhand != null && itemOffhand.type == XC.config.materialAimDownSights ) {
            val itemMeta = itemOffhand.getItemMeta()
            if ( itemMeta.hasCustomModelData() && itemMeta.getCustomModelData() < XC.MAX_GUN_CUSTOM_MODEL_ID ) {
                equipment.setItemInOffHand(ItemStack(Material.AIR, 1))
            }
        }
    }


    /**
     * Return true if item stack is an aim down sights model.
     */
    public fun isAimDownSightsModel(item: ItemStack): Boolean {
        if ( item.getType() == XC.config.materialAimDownSights ) {
            val itemMeta = item.getItemMeta()
            if ( itemMeta.hasCustomModelData() ) {
                return itemMeta.getCustomModelData() < XC.MAX_GUN_CUSTOM_MODEL_ID
            }
        }

        return false
    }

    /**
     * Return true if player is crawling.
     */
    public fun isCrawling(player: Player): Boolean {
        return XC.crawling.contains(player.getUniqueId())
    }

    /**
     * Main engine update, runs on each tick
     */
    internal fun update() {
        val tUpdateStart = XC.debugNanoTime() // timing probe

        XC.debugTimings.tick()
        XC.runBenchmarkProjectiles() // debugging

        val tShootSystem = XC.debugNanoTime() // timing probe

        // timestamp for beginning update tick
        val timestamp = System.currentTimeMillis()

        // wear hats
        XC.wearHatRequests = wearHatSystem(XC.wearHatRequests)

        // run pipelined player movement check, for sway modifier
        val (playerNewSpeed, playerNewLocation) = playerSpeedSystem(XC.playerSpeed, XC.playerPreviousLocation)
        XC.playerSpeed = playerNewSpeed
        XC.playerPreviousLocation = playerNewLocation

        // crawl systems
        XC.crawlStartQueue = startCrawlSystem(XC.crawlStartQueue)
        XC.crawlStopQueue = stopCrawlSystem(XC.crawlStopQueue)
        XC.crawling = crawlRefreshSystem(XC.crawling)
        // finish crawl to shoot requests
        val crawlRequestFinishTasks = ArrayList<CrawlToShootRequestFinish>()
        val crawlRequestCancelTasks = ArrayList<CrawlToShootRequestCancel>()
        XC.playerCrawlRequestFinishQueue.drainTo(crawlRequestFinishTasks)
        XC.playerCrawlRequestCancelQueue.drainTo(crawlRequestCancelTasks)
        finishCrawlToShootRequestSystem(crawlRequestFinishTasks)
        cancelCrawlToShootRequestSystem(crawlRequestCancelTasks)

        // run gun controls systems (these emit new queues for next tick)
        XC.playerAimDownSightsRequests = gunAimDownSightsSystem(XC.playerAimDownSightsRequests)
        XC.playerGunCleanupRequests = playerGunCleanupSystem(XC.playerGunCleanupRequests)
        XC.itemGunCleanupRequests = gunItemCleanupSystem(XC.itemGunCleanupRequests)
        XC.playerGunSelectRequests = gunSelectSystem(XC.playerGunSelectRequests, timestamp)
        XC.playerReloadRequests = gunPlayerReloadSystem(XC.playerReloadRequests, timestamp)
        XC.autoFiringPackets = autoFireRequestSystem(XC.playerAutoFireRequests, XC.autoFiringPackets, timestamp) // do auto fire request before single/burst fire
        XC.playerShootRequests = gunPlayerShootSystem(XC.playerShootRequests, timestamp)
        XC.burstFiringPackets = burstFireSystem(XC.burstFiringPackets, timestamp)
        XC.autoFiringPackets = autoFireSystem(XC.autoFiringPackets)
        XC.playerRecoil = recoilRecoverySystem(XC.playerRecoil)
        XC.crawlToShootRequestQueue = requestCrawlToShootSystem(XC.crawlToShootRequestQueue, timestamp)

        // queues that need to be manually re-created (cannot easily return tuples in kotlin/java)
        XC.playerAutoFireRequests = ArrayList()

        // ready and throw throwable systems
        // (tick for thrown throwable objects done with projectiles
        // because hitboxes needed)
        XC.readyThrowableRequests = requestReadyThrowableSystem(XC.readyThrowableRequests)
        XC.throwThrowableRequests = requestThrowThrowableSystem(XC.throwThrowableRequests)
        XC.droppedThrowables = droppedThrowableSystem(XC.droppedThrowables)
        XC.readyThrowables = tickReadyThrowableSystem(XC.readyThrowables)

        // landmine systems
        // (explosion handling done after hitboxes created in projectiles update block)
        XC.landmineFinishUseRequests = landmineFinishUseSystem(XC.landmineFinishUseRequests) // note: finish using tick N-1 requests
        XC.landmineActivationRequests = landmineActivationSystem(XC.landmineActivationRequests)
        
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
        for ( (worldId, projSys) in this.projectileSystems ) {
            // first gather visited chunks for throwable items
            // (for potential explosion/entity hit calculations)
            val visitedChunks = XC.thrownThrowables[worldId]?.let { throwables -> getThrownThrowableVisitedChunksSystem(throwables) } ?: LinkedHashSet()
            XC.landmineExplosions[worldId]?.let { explosions -> visitedChunks.addAll(getLandmineExplosionVisitedChunksSystem(explosions)) }

            // run projectile system
            val (hitboxes, hitBlocksQueue, hitEntitiesQueue) = projSys.update(visitedChunks)
            
            // handle hit blocks and entities
            // if ( hitBlocksQueue.size > 0 ) println("HIT BLOCKS: ${hitBlocksQueue}")
            // if ( hitEntitiesQueue.size > 0 ) println("HIT ENTITIES: ${hitEntitiesQueue}")

            for ( hitBlock in hitBlocksQueue ) {
                hitBlock.gun.hitBlockHandler(hitboxes, hitBlock.gun, hitBlock.location, hitBlock.block, hitBlock.source)
            }

            for ( hitEntity in hitEntitiesQueue ) {
                hitEntity.gun.hitEntityHandler(hitboxes, hitEntity.gun, hitEntity.location, hitEntity.entity, hitEntity.source, hitEntity.distance)
            }

            // per-world throwable tick systems (needs hitboxes)
            XC.expiredThrowables[worldId] = handleExpiredThrowableSystem(XC.expiredThrowables[worldId] ?: listOf(), hitboxes)
            XC.thrownThrowables[worldId] = tickThrownThrowableSystem(XC.thrownThrowables[worldId] ?: listOf(), hitboxes)

            // per-world landmine tick systems (needs hitboxes)
            XC.landmineExplosions[worldId] = landmineHandleExplosionSystem(XC.landmineExplosions[worldId] ?: listOf(), hitboxes)
        }

        // ================================================
        // SCHEDULE ALL ASYNC TASKS (particles, packets)
        // ================================================
        val tStartPackets = XC.debugNanoTime() // timing probe

        val particleBulletTrails = XC.particleBulletTrailQueue
        val particleBulletImpacts = XC.particleBulletImpactQueue
        val particleExplosions = XC.particleExplosionQueue
        val gunAmmoInfoMessages = XC.gunAmmoInfoMessageQueue
        val soundPackets = XC.soundQueue
        val recoilPackets = XC.recoilQueue
        val blockCrackAnimations = XC.blockCrackAnimationQueue

        XC.particleBulletTrailQueue = ArrayList()
        XC.particleBulletImpactQueue = ArrayList()
        XC.particleExplosionQueue = ArrayList()
        XC.gunAmmoInfoMessageQueue = ArrayList()
        XC.soundQueue = ArrayList()
        XC.recoilQueue = ArrayList()
        XC.blockCrackAnimationQueue = ArrayList()

        if ( XC.config.asyncPackets ) {
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
                TaskSpawnParticleExplosion(particleExplosions),
            )
            Bukkit.getScheduler().runTaskAsynchronously(
                XC.plugin!!,
                TaskAmmoInfoMessages(gunAmmoInfoMessages),
            )
            Bukkit.getScheduler().runTaskAsynchronously(
                XC.plugin!!,
                TaskSounds(soundPackets),
            )
        }
        else {
            // sync
            TaskSpawnParticleBulletTrails(particleBulletTrails).run()
            TaskSpawnParticleBulletImpacts(particleBulletImpacts).run()
            TaskSpawnParticleExplosion(particleExplosions).run()
            TaskAmmoInfoMessages(gunAmmoInfoMessages).run()
            TaskSounds(soundPackets).run()
        }


        // custom packets (only if ProtocolLib is available)
        if ( XC.usingProtocolLib ) {
            val protocolManager = ProtocolLibrary.getProtocolManager()

            // block crack animations
            Bukkit.getScheduler().runTaskAsynchronously(
                XC.plugin!!,
                TaskBroadcastBlockCrackAnimations(protocolManager, blockCrackAnimations),
            )

            // player recoil from gun firing
            Bukkit.getScheduler().runTaskAsynchronously(
                XC.plugin!!,
                TaskRecoil(protocolManager, recoilPackets),
            )

            // sync
            // TaskBroadcastBlockCrackAnimations(ProtocolLibrary.getProtocolManager(), blockCrackAnimations).run()
        }

        // save kill/death stats system
        XC.playerDeathRecordSaveCounter -= 1
        if ( XC.playerDeathRecordSaveCounter <= 0 ) {
            XC.playerDeathRecordSaveCounter = XC.config.playerDeathRecordSaveInterval
            
            if ( XC.playerDeathRecords.size > 0 ) {
                val deathRecords = XC.playerDeathRecords
                XC.playerDeathRecords = ArrayList()

                Bukkit.getScheduler().runTaskAsynchronously(
                    XC.plugin!!,
                    TaskSavePlayerDeathRecords(deathRecords, XC.config.playerDeathLogSaveDir),
                )
            }
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