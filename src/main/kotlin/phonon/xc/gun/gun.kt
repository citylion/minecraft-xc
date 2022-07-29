/**
 * Contain gun definition.
 */
package phonon.xc.gun

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.util.logging.Logger
import java.util.UUID
import kotlin.math.min
import kotlin.math.max
import org.tomlj.Toml
import org.bukkit.Color
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.entity.Entity
import org.bukkit.util.Vector
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.persistence.PersistentDataContainer
import phonon.xc.XC
import phonon.xc.gun.getGunHitEntityHandler
import phonon.xc.gun.getGunHitBlockHandler
import phonon.xc.gun.noEntityHitHandler
import phonon.xc.gun.noBlockHitHandler
import phonon.xc.utils.mapToObject
import phonon.xc.utils.damage.DamageType
import phonon.xc.utils.particle.ParticlePacket


/**
 * Put cap on max ammo allowed in a gun.
 * This is because guns generate all possible lore strings with ammo.
 * Protect against user making guns with massive amount of ammo 9001
 * that then generate huge arrays of strings.
 */
public const val GUN_MAX_AMMO_ALLOWED: Int = 1024

/**
 * Gun single shot firing mode types.
 */
public enum class GunSingleFireMode {
    NONE,
    SINGLE,
    BURST,
    ;

    companion object {
        /**
         * Match name to type. Case-insensitive.
         * If none found, will return null.
         */
        public fun match(name: String): GunSingleFireMode? {
            return when (name.uppercase()) {
                "NONE" -> NONE
                "SINGLE" -> SINGLE
                "BURST" -> BURST
                else -> null
            }
        }
    }
}


/**
 * Gun type categories. Currently just metadata.
 */
public enum class GunType {
    PISTOL,
    RIFLE,
    SHOTGUN,
    SUB_MACHINE_GUN,
    LIGHT_MACHINE_GUN,
    HEAVY_MACHINE_GUN,
    SNIPER,
    ANTI_TANK_RIFLE,
    FLAMETHROWER,
    RPG,
    ;
}

/**
 * Interface for sending fake aim down sights item models to client.
 * Wrapper for different NMS version internal packet handling.
 */
public interface AimDownSightsModel {
    /**
     * Send packet to player creating an aim down sights model
     * in their offhand.
     */
    public fun create(player: Player)

    /**
     * Send packet to player removing aim down sights model
     * if item is in their offhand.
     */
    public fun destroy(player: Player)
}

/**
 * Common gun object used by all guns.
 * This is an immutable object. When properties need to change,
 * create a new gun using kotlin data class `copy()` which can
 * alter certain properties while leaving others the same.
 */
public data class Gun(
    // gun id, used for mapping custom models => gun
    public val id: Int = Int.MAX_VALUE, // invalid
    
    // gun type
    public val type: GunType = GunType.RIFLE,

    // gun item/visual properties
    public val itemName: String = "gun",
    public val itemLore: List<String> = listOf(),
    public val itemModelDefault: Int = 0,     // normal model (custom model data id)
    public val itemModelEmpty: Int = -1,      // when gun out of ammo
    public val itemModelReload: Int = -1,     // when gun is reloading
    public val itemModelAimDownSights: Int = -1, // when using iron sights

    // equiped properties
    // slowness while equiped (if > 0)
    public val equipDelayMillis: Long = 500, // delay before player can fire after selecting weapon
    public val equipSlowness: Int = 0,
    public val equipNoSprint: Boolean = false,

    // require crawl (prone position) to shoot
    public val crawlRequired: Boolean = false,
    public val crawlTimeMillis: Long = 1000, // time to crawl to shoot in milliseconds

    // reload [ms]
    public val reloadTimeMillis: Long = 1500,

    // semiauto/regular shoot properties [ms]
    public val singleFireMode: GunSingleFireMode = GunSingleFireMode.SINGLE,
    public val burstFireCount: Int = 3,
    public val burstFireDelayTicks: Int = 2,
    public val shootDelayMillis: Long = 500,

    // automatic fire rate properties
    public val autoFire: Boolean = false,     // automatic weapon
    public val autoFireDelayTicks: Int = 2,   // auto fire rate in ticks
    public val autoFireSlowness: Int = 2,     // auto fire slowness level

    // ammo
    public val ammoId: Int = -1,
    public val ammoMax: Int = 10,
    public val ammoPerReload: Int = -1,       // if -1, reload to max. otherwise: ammo + ammoPerReload
    public val ammoIgnore: Boolean = false,   // if true, ignores out of ammo
    
    // sway
    public val swayBase: Double = 0.05,           // base sway (how random projectiles are)
    public val swaySpeedMultiplier: Double = 2.0, // sway multiplier while moving, depends on player velocity
    public val swayAimDownSights: Double = 0.25,  // sway while aiming down sights
    public val swayRideHorse: Double = 1.5,       // sway while riding horse
    public val swayRideBoat: Double = 1.5,        // sway while riding boat
    public val swayRideArmorStand: Double = 1.0,  // sway while riding armor stand

    // recoil
    public val recoilSingleHorizontal: Double = 2.0,
    public val recoilSingleVertical: Double = 5.0,
    public val recoilAutoHorizontal: Double = 2.0,
    public val recoilAutoVertical: Double = 5.0,
    public val recoilSingleFireRamp: Double = 0.1,
    public val recoilAutoFireRamp: Double = 0.1,
    
    // number of projectiles fired
    public val projectileCount: Int = 1,

    // projectile velocity in blocks/tick => (20*vel) m/s
    // physical velocities of ~900 m/s would require vel ~ 45.0
    // but usually this is too fast ingame (makes projectiles too hitscan-y)
    // so instead opt for lower velocity + lower gravity as default
    public val projectileVelocity: Float = 16.0f,

    // projectile gravity in blocks/tick^2 => (400*g) m/s^2
    // physical world gravity would be 0.025 to give 10 m/s^2
    // NOTE: THIS IS A POSITIVE NUMBER
    public val projectileGravity: Float = 0.025f,

    // max lifetime in ticks before despawning
    public val projectileLifetime: Int = 400, // ~20 seconds

    // max projectile distance in blocks before despawning
    public val projectileMaxDistance: Float = 128.0f, // = view distance of 8 chunks

    // main projectile damage
    public val projectileDamage: Double = 4.0,
    public val projectileArmorReduction: Double = 0.5,
    public val projectileResistanceReduction: Double = 0.5,
    public val projectileDamageType: DamageType = DamageType.BULLET,
    public val projectileDamageMin: Double = 4.0,
    public val projectileDamageDropDistance: Double = 0.0,

    // projectile particle config
    public val projectileParticleType: Particle = Particle.REDSTONE,
    public val projectileParticleSize: Float = 0.35f,
    public val projectileParticleColor: Color = Color.WHITE,
    public val projectileParticleSpacing: Double = 1.5,
    public val projectileParticleForceRender: Boolean = true,

    // flag for projectile block hit particles and crack animation
    public val projectileBlockHitParticles: Boolean = true,

    // explosion damage and radius and falloff (unused if no explosion)
    public val explosionDamage: Double = 8.0,
    public val explosionMaxDistance: Double = 8.0,        // max distance for checking entities
    public val explosionRadius: Double = 1.0,
    public val explosionFalloff: Double = 2.0,            // damage/block falloff
    public val explosionArmorReduction: Double = 0.5,     // damage/armor point
    public val explosionBlastProtReduction: Double = 1.0, // damage/blast protection
    public val explosionDamageType: DamageType = DamageType.EXPLOSIVE_SHELL,
    public val explosionBlockDamagePower: Float = 0f,     // explosion block damage power level
    public val explosionFireTicks: Int = 0,               // if explosion should set targets on fire
    public val explosionParticles: ParticlePacket = ParticlePacket.placeholderExplosion(),

    // handler on block hit
    public val hitBlockHandler: GunHitBlockHandler = noBlockHitHandler,

    // handler on entity hit
    public val hitEntityHandler: GunHitEntityHandler = entityDamageHitHandler,
    
    // hit fire ticks (when hit handler is fire)
    public val hitFireTicks: Int = 0,
    // probability setting a hit block on fire
    public val hitBlockFireProbability: Double = 0.04,

    // sounds
    public val soundShoot: String = "gun_shoot",
    public val soundShootVolume: Float = 1f,
    public val soundShootPitch: Float = 1f,
    public val soundEmpty: String = "gun_empty",
    public val soundEmptyVolume: Float = 1f,
    public val soundEmptyPitch: Float = 1f,
    public val soundReloadStart: String = "gun_reload_start",
    public val soundReloadStartVolume: Float = 1f,
    public val soundReloadStartPitch: Float = 1f,
    public val soundReloadFinish: String = "gun_reload_finish",
    public val soundReloadFinishVolume: Float = 1f,
    public val soundReloadFinishPitch: Float = 1f,
) {

    // This contains an array of all possible combinations of
    // ammo string (e.g. "Ammo: 4/10") and item lore.
    // Each index is: loreWithAmmo[ammo] => List<String> item lore 
    public val itemDescriptionForAmmo: List<List<String>>

    // damage drop multiplier
    private val damageDropDistanceMultiplier = if ( this.projectileDamageDropDistance > 0.0 ) {
        ( this.projectileDamage - this.projectileDamageMin ) / this.projectileDamageDropDistance
    } else {
        0.0
    }

    /**
     * Convert this single/burst fire delay into an attribute modifier
     * attack speed (to adjust the minecraft attack cooldown bar).
     * 
     * generic.attack_speed
     *      Determines recharging rate of attack strength.
     *      Value is the number of full-strength attacks per second. 
     * 
     * https://minecraft.fandom.com/wiki/Attribute#Attributes_for_players
     * 
     * Formula is (in seconds)
     *     delay = 1s / generic_attack_speed = 1s / (4.0 + modifier)
     * 
     * We want to calculate modifier to get the desired delay:
     *     shootDelayMillis = 1000 / (4.0 + modifier)
     *     modifier = (1000 / shootDelayMillis) - 4.0
     */
    public val attackSpeedAttributeModifierValue: Double = (1000.0 / this.shootDelayMillis.toDouble()) - 4.0
    public val attackSpeedAttributeModifier: AttributeModifier = AttributeModifier(
        UUID.randomUUID(),
        "gun.attackSpeed",
        this.attackSpeedAttributeModifierValue,
        AttributeModifier.Operation.ADD_NUMBER,
        EquipmentSlot.HAND,
    )

    init {
        // generate all possible item descriptions with different ammo values
        val itemDescriptionForAmmoBuf = ArrayList<List<String>>(this.ammoMax + 1)
        for (i in 0..this.ammoMax) {
            val itemDescription: ArrayList<String> = arrayListOf("${ChatColor.GRAY}Ammo: ${i}/${this.ammoMax}")
            // append lore
            itemDescription.addAll(this.itemLore)
            
            itemDescriptionForAmmoBuf.add(itemDescription)
        }

        this.itemDescriptionForAmmo = itemDescriptionForAmmoBuf
    }

    /**
     * Returns the item lore for the given ammo value.
     * Coerces ammo value to range [0, ammoMax].
     */
    public fun getItemDescriptionForAmmo(ammo: Int): List<String> {
        val index = ammo.coerceIn(0, this.ammoMax)
        return this.itemDescriptionForAmmo[index]
    }

    /**
     * Create a projectile using this gun's properties.
     */
    public fun createProjectile(
        source: Entity,
        shootLocation: Location,
        shootDirection: Vector,
    ): Projectile {
        return Projectile(
            gun = this,
            source = source,
            x = shootLocation.x.toFloat(),
            y = shootLocation.y.toFloat(),
            z = shootLocation.z.toFloat(),
            dirX = shootDirection.x.toFloat(),
            dirY = shootDirection.y.toFloat(),
            dirZ = shootDirection.z.toFloat(),
            speed = this.projectileVelocity,
            gravity = this.projectileGravity,
            maxLifetime = this.projectileLifetime,
            maxDistance = this.projectileMaxDistance,
        )
    }

    /**
     * Create a new ItemStack from gun properties.
     */
    public fun toItemStack(ammo: Int = Int.MAX_VALUE): ItemStack {
        val item = ItemStack(XC.config.materialGun, 1)
        val itemMeta = item.getItemMeta()
        
        // name
        itemMeta.setDisplayName("${ChatColor.RESET}${this.itemName}")
        
        // model
        itemMeta.setCustomModelData(this.itemModelDefault)

        // ammo (IMPORTANT: actual ammo count used for shooting/reload logic)
        val ammoCount = min(ammo, this.ammoMax)
        val itemData = itemMeta.getPersistentDataContainer()
        itemData.set(XC.namespaceKeyItemAmmo!!, PersistentDataType.INTEGER, min(ammoCount, this.ammoMax))
        
        // begin item description with ammo count
        val itemDescription = this.getItemDescriptionForAmmo(ammo)
        itemMeta.setLore(itemDescription.toList())

        // add item cooldown
        itemMeta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, this.attackSpeedAttributeModifier)

        item.setItemMeta(itemMeta)

        return item
    }

    /**
     * Calculate projectile damage at a distance after applying
     * damage drop.
     */
    public fun projectileDamageAtDistance(distance: Double): Double {
        return if ( this.damageDropDistanceMultiplier > 0.0 ) {
            max(this.projectileDamageMin, this.projectileDamage - (distance * this.damageDropDistanceMultiplier))
        } else {
            this.projectileDamage
        }
    }

    
    companion object {
        /**
         * Parse and return a Gun from a `gun.toml` file.
         * Return null gun if something fails or no file found.
         */
        public fun fromToml(source: Path, logger: Logger? = null): Gun? {
            try {
                val toml = Toml.parse(source)

                // map with keys as constructor property names
                val properties = HashMap<String, Any>()

                // parse toml file into properties
                
                // gun id
                toml.getLong("id")?.let { properties["id"] = it.toInt() }

                // item properties
                toml.getTable("item")?.let { item -> 
                    item.getString("name")?.let { properties["itemName"] = ChatColor.translateAlternateColorCodes('&', it) }
                    item.getArray("lore")?.let { properties["itemLore"] = it.toList().map { s -> s.toString() } }
                }

                // item model properties
                toml.getTable("model")?.let { model -> 
                    model.getLong("default")?.let { properties["itemModelDefault"] = it.toInt() }
                    model.getLong("empty")?.let { properties["itemModelEmpty"] = it.toInt() }
                    model.getLong("reload")?.let { properties["itemModelReload"] = it.toInt() }
                    model.getLong("ads")?.let { properties["itemModelAimDownSights"] = it.toInt()}
                }

                // sounds
                toml.getTable("sound")?.let { sound -> 
                    sound.getString("shoot")?.let { properties["soundShoot"] = it }
                    sound.getString("reload")?.let { properties["soundReload"] = it }
                    sound.getString("empty")?.let { properties["soundEmpty"] = it }
                }

                // equip
                toml.getTable("equip")?.let { equip ->
                    equip.getLong("delay")?.let { properties["equipDelayMillis"] = it }
                    equip.getLong("slowness")?.let { properties["equipSlowness"] = it.toInt() }
                    equip.getBoolean("no_sprint")?.let { properties["equipNoSprint"] = it }
                }

                // crawl
                toml.getTable("crawl")?.let { crawl ->
                    crawl.getBoolean("required")?.let { properties["crawlRequired"] = it }
                    crawl.getLong("time")?.let { properties["crawlTimeMillis"] = it }
                }

                // ammo
                toml.getTable("ammo")?.let { ammo ->
                    ammo.getLong("id")?.let { properties["ammoId"] = it.toInt() }
                    ammo.getLong("max")?.let { properties["ammoMax"] = min(GUN_MAX_AMMO_ALLOWED, it.toInt()) }
                    ammo.getLong("per_reload")?.let { properties["ammoPerReload"] = it.toInt() }
                    ammo.getBoolean("ignore")?.let { properties["ammoIgnore"] = it }
                }

                // reloading
                toml.getTable("reload")?.let { reload ->
                    reload.getLong("time")?.let { properties["reloadTimeMillis"] = it }
                }

                // [left-click] shooting (regular/semiauto) single or burst fire
                toml.getTable("shoot")?.let { shoot ->
                    shoot.getString("fire_mode")?.let { name ->
                        val fireMode = GunSingleFireMode.match(name)
                        if ( fireMode != null ) {
                            properties["singleFireMode"] = fireMode
                        } else {
                            logger?.warning("Unknown firing mode type for [shoot.fire_mode]: ${name}")
                        }
                    }
                    shoot.getLong("burst_count")?.let { properties["burstFireCount"] = it.toInt() }
                    shoot.getLong("burst_delay")?.let { properties["burstFireDelayTicks"] = it.toInt() }
                    shoot.getLong("delay")?.let { properties["shootDelayMillis"] = it }
                }

                // [right-click] automatic fire
                toml.getTable("automatic")?.let { auto ->
                    auto.getBoolean("enabled")?.let { properties["autoFire"] = it }
                    auto.getLong("delay_ticks")?.let { properties["autoFireDelayTicks"] = it.toInt() }
                    auto.getLong("slowness")?.let { properties["autoFireSlowness"] = it.toInt() }
                }

                // sway
                toml.getTable("sway")?.let { sway ->
                    sway.getDouble("base")?.let { properties["swayBase"] = it }
                    sway.getDouble("speed_multiplier")?.let { properties["swaySpeedMultiplier"] = it }
                    sway.getDouble("aim_down_sights")?.let { properties["swayAimDownSights"] = it }
                    sway.getDouble("ride_horse")?.let { properties["swayRideHorse"] = it }
                    sway.getDouble("ride_boat")?.let { properties["swayRideBoat"] = it }
                    sway.getDouble("ride_armor_stand")?.let { properties["swayRideArmorStand"] = it }
                }

                // recoil
                toml.getTable("recoil")?.let { recoil ->
                    recoil.getDouble("single_horizontal")?.let { properties["recoilSingleHorizontal"] = it }
                    recoil.getDouble("single_vertical")?.let { properties["recoilSingleVertical"] = it }
                    recoil.getDouble("auto_horizontal")?.let { properties["recoilAutoHorizontal"] = it }
                    recoil.getDouble("auto_vertical")?.let { properties["recoilAutoVertical"] = it }
                    recoil.getDouble("single_fire_ramp")?.let { properties["recoilSingleFireRamp"] = it }
                    recoil.getDouble("auto_fire_ramp")?.let { properties["recoilAutoFireRamp"] = it }
                }

                // hit handlers
                toml.getTable("hit")?.let { hit ->
                    hit.getString("entity")?.let { handlerName ->
                        val handler = getGunHitEntityHandler(handlerName)
                        if ( handler == null ) {
                            logger?.warning("Unknown entity hit handler: ${handlerName}")
                        }
                        properties["hitEntityHandler"] = handler ?: noEntityHitHandler
                    }
                    hit.getString("block")?.let { handlerName ->
                        val handler = getGunHitBlockHandler(handlerName)
                        if ( handler == null ) {
                            logger?.warning("Unknown block hit handler: ${handlerName}")
                        }
                        properties["hitBlockHandler"] = handler ?: noBlockHitHandler
                    }

                    // fire properties
                    hit.getLong("fire_ticks")?.let { properties["hitFireTicks"] = it.toInt() }
                    hit.getDouble("block_fire_probability")?.let { properties["hitBlockFireProbability"] = it }
                }

                // projectile
                toml.getTable("projectile")?.let { projectile ->
                    projectile.getLong("count")?.let { properties["projectileCount"] = it.toInt() }
                    projectile.getDouble("damage")?.let { properties["projectileDamage"] = it }
                    projectile.getDouble("armor_reduction")?.let { properties["projectileArmorReduction"] = it }
                    projectile.getDouble("resist_reduction")?.let { properties["projectileResistanceReduction"] = it }
                    projectile.getDouble("velocity")?.let { properties["projectileVelocity"] = it.toFloat() }
                    projectile.getDouble("gravity")?.let { properties["projectileGravity"] = it.toFloat() }
                    projectile.getLong("lifetime")?.let { properties["projectileLifetime"] = it.toInt() }
                    projectile.getDouble("max_distance")?.let { properties["projectileMaxDistance"] = it.toFloat() }
                    projectile.getString("damage_type")?.let { name ->
                        val damageType = DamageType.match(name)
                        if ( damageType != null ) {
                            properties["projectileDamageType"] = damageType
                        } else {
                            logger?.warning("Unknown damage type: ${name}")
                        }
                    }
                    projectile.getDouble("damage_min")?.let { properties["projectileDamageMin"] = it }
                    projectile.getDouble("damage_drop_distance")?.let { properties["projectileDamageDropDistance"] = it }
                }

                // projectile particles
                toml.getTable("projectile.particles")?.let { particles ->
                    particles.getString("type")?.let { ty -> properties["projectileParticleType"] = Particle.valueOf(ty) }

                    // parse particle color RGB integer array [r, g, b]
                    particles.getArray("color")?.let { arr ->
                        properties["projectileParticleColor"] = Color.fromRGB(
                            arr.getLong(0).toInt().coerceIn(0, 255),
                            arr.getLong(1).toInt().coerceIn(0, 255),
                            arr.getLong(2).toInt().coerceIn(0, 255),
                        )
                    }

                    particles.getDouble("size")?.let { properties["projectileParticleSize"] = it.toFloat() }
                    particles.getDouble("spacing")?.let { properties["projectileParticleSpacing"] = it }
                    particles.getBoolean("force_render")?.let { properties["projectileParticleForceRender"] = it }
                    particles.getBoolean("block_hit_particles")?.let { properties["projectileBlockHitParticles"] = it }
                }

                // explosion
                toml.getTable("explosion")?.let { explosion ->
                    explosion.getDouble("damage")?.let { properties["explosionDamage"] = it }
                    explosion.getDouble("max_distance")?.let { properties["explosionMaxDistance"] = it }
                    explosion.getDouble("radius")?.let { properties["explosionRadius"] = it }
                    explosion.getDouble("falloff")?.let { properties["explosionFalloff"] = it }
                    explosion.getDouble("armor_reduction")?.let { properties["explosionArmorReduction"] = it }
                    explosion.getDouble("blast_prot_reduction")?.let { properties["explosionBlastProtReduction"] = it }
                    explosion.getLong("fire_ticks")?.let { properties["explosionFireTicks"] = it.toInt() }
                    explosion.getString("damage_type")?.let { name ->
                        val damageType = DamageType.match(name)
                        if ( damageType != null ) {
                            properties["explosionDamageType"] = damageType
                        } else {
                            logger?.warning("Unknown damage type: ${name}")
                        }
                    }
                    explosion.getDouble("block_damage_power")?.let { properties["explosionBlockDamagePower"] = it.toFloat() }
                }

                // explosion particles
                toml.getTable("explosion.particles")?.let { particles ->
                    val particleType = particles.getString("type")?.let { ty ->
                        try {
                            Particle.valueOf(ty)
                        } catch ( err: Exception ) {
                            err.printStackTrace()
                            Particle.EXPLOSION_LARGE
                        }
                    } ?: Particle.EXPLOSION_LARGE
                    val count = particles.getLong("count")?.toInt() ?: 1
                    val randomX = particles.getDouble("random_x") ?: 0.0
                    val randomY = particles.getDouble("random_y") ?: 0.0
                    val randomZ = particles.getDouble("random_z") ?: 0.0
                    properties["explosionParticles"] = ParticlePacket(
                        particle = particleType,
                        count = count,
                        randomX = randomX,
                        randomY = randomY,
                        randomZ = randomZ
                    )
                }
                
                // sounds
                toml.getTable("sound")?.let { sound ->
                    if ( sound.isTable("shoot") ) {
                        sound.getTable("shoot")?.let { shootSound ->
                            shootSound.getString("name")?.let { properties["soundShoot"] = it }
                            shootSound.getDouble("volume")?.let { properties["soundShootVolume"] = it.toFloat() }
                            shootSound.getDouble("pitch")?.let { properties["soundShootPitch"] = it.toFloat() }
                        }
                    } else {
                        sound.getString("shoot")?.let { properties["soundShoot"] = it }
                    }

                    if ( sound.isTable("empty") ) {
                        sound.getTable("empty")?.let { shootSound ->
                            shootSound.getString("name")?.let { properties["soundEmpty"] = it }
                            shootSound.getDouble("volume")?.let { properties["soundEmptyVolume"] = it.toFloat() }
                            shootSound.getDouble("pitch")?.let { properties["soundEmptyPitch"] = it.toFloat() }
                        }
                    } else {
                        sound.getString("empty")?.let { properties["soundEmpty"] = it }
                    }

                    if ( sound.isTable("reload_start") ) {
                        sound.getTable("reload_start")?.let { shootSound ->
                            shootSound.getString("name")?.let { properties["soundReloadStart"] = it }
                            shootSound.getDouble("volume")?.let { properties["soundReloadStartVolume"] = it.toFloat() }
                            shootSound.getDouble("pitch")?.let { properties["soundReloadStartPitch"] = it.toFloat() }
                        }
                    } else {
                        sound.getString("reload_start")?.let { properties["soundReloadStart"] = it }
                    }

                    if ( sound.isTable("reload_finish") ) {
                        sound.getTable("reload_finish")?.let { shootSound ->
                            shootSound.getString("name")?.let { properties["soundReloadFinish"] = it }
                            shootSound.getDouble("volume")?.let { properties["soundReloadFinishVolume"] = it.toFloat() }
                            shootSound.getDouble("pitch")?.let { properties["soundReloadFinishPitch"] = it.toFloat() }
                        }
                    } else {
                        sound.getString("reload_finish")?.let { properties["soundReloadFinish"] = it }
                    }
                }

                // println("CREATING GUN WITH PROPERTIES: ${properties}")

                return mapToObject(properties, Gun::class)
            } catch (e: Exception) {
                logger?.warning("Failed to parse gun file: ${source.toString()}, ${e}")
                e.printStackTrace()
                return null
            }
        }
        
        /**
         * Load gun from string file, return null if path not found or
         * if loading encounters an error.
         */
        public fun loadFilename(filename: String, logger: Logger? = null): Gun? {
            val p = Paths.get(filename)
            if ( Files.isRegularFile(p) ) {
                try {
                    return Gun.fromToml(p, logger)
                } catch (err: Exception) {
                    logger?.warning("Failed to parse gun file: ${filename}, ${err}")
                    return null
                }
            } else {
                return null
            }
        }
        
        /**
         * Load gun from string file, return default gun if not found.
         */
        public fun loadFilenameOrDefault(filename: String, default: Gun, logger: Logger? = null): Gun {
            val p = Paths.get(filename)
            if ( Files.isRegularFile(p) ) {
                try {
                    return Gun.fromToml(p, logger) ?: default
                } catch (err: Exception) {
                    logger?.warning("Failed to parse gun file: ${filename}, ${err}")
                    return default
                }
            } else {
                return default
            }
        }
    }
}