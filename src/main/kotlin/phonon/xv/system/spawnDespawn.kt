/**
 * Systems for in-game spawning and despawning of vehicles.
 * These manage the request and progress timer for spawn/despawn process.
 * When spawn/despawn is complete, these systems generate a  create/destroy
 * request which finalizes the process. The "create" and "destroy" systems
 * are responsible for the actual creation and destruction of vehicles.
 */

package phonon.xv.system

import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import phonon.xv.XV
import phonon.xv.core.Vehicle
import phonon.xv.core.VehicleComponentType
import phonon.xv.core.VehiclePrototype
import phonon.xv.system.CreateVehicleRequest
import phonon.xv.system.DestroyVehicleRequest
import phonon.xv.util.TaskProgress
import phonon.xv.util.progressBar10
import phonon.xv.util.Message
import phonon.xv.util.drain
import phonon.xv.util.item.addIntId
import phonon.xv.util.item.itemInMainHandEquivalentTo

/**
 * A request to spawn a vehicle, containing optional creation sources
 * (player, item, etc.) which are used to customize creation parameters.
 */
public data class SpawnVehicleRequest(
    val prototype: VehiclePrototype,
    val location: Location? = null,
    val player: Player? = null,
    val item: ItemStack? = null,
    // if true, skip spawn timer 
    val skipTimer: Boolean = false,
)

/**
 * Signal to finish spawning a vehicle, containing spawn source
 */
public data class SpawnVehicleFinish(
    val prototype: VehiclePrototype,
    val location: Location? = null,
    val player: Player? = null,
    val item: ItemStack? = null,
    val itemIdTag: Int? = null,
)

/**
 * A request to despawn a vehicle, containing despawn source
 * and options.
 */
public data class DespawnVehicleRequest(
    val vehicle: Vehicle,
    val player: Player? = null,
    // if true, drop item for vehicle
    val dropItem: Boolean = true,
    // if true, despawn even if there are passengers
    val force: Boolean = false,
    // if true, skip despawn timer
    val skipTimer: Boolean = false,
)

/**
 * Signal to finish despawning a vehicle.
 */
public data class DespawnVehicleFinish(
    val vehicle: Vehicle,
    val player: Player? = null,
    // if true, drop item for vehicle
    val dropItem: Boolean = true,
    // if true, despawn even if there are passengers
    val force: Boolean = false,
)

/**
 * Handle vehicle spawn requests, from player vehicle item or command.
 * If the vehicle has a "spawn" component, this initiates a spawn
 * sequence:
 *     1. Begin spawn progress bar timer
 *     2. Handle spawn tick progress on async thread. If player moves
 *        past a certain distance, or swaps item, etc. cancel spawn.
 *     3. When progress finished, queue finish spawn request on main thread.
 * 
 * If the vehicle does not have a "spawn" component, this bypasses the
 * spawn progress and goes directly to the finish spawn request.
 * 
 * Actual vehicle creation handled inside create system `systemCreateVehicle`.
 */
public fun XV.systemSpawnVehicle(
    requests: Queue<SpawnVehicleRequest>,
    finishQueue: ConcurrentLinkedQueue<SpawnVehicleFinish>,
) {
    val xv = this

    for ( request in requests.drain() ) {
        val (
            prototype,
            location,
            player,
            item,
            skipTimer,
        ) = request

        try {
            if ( player !== null ) {
                // attach id tag to item, so we can check if item changed during spawn
                val itemIdTag = item?.addIntId()

                val taskSpawn = TaskProgress(
                    timeTaskMillis = 4000,
                    player = player,
                    initialItemInHand = item,
                    itemIdTag = itemIdTag,
                    maxMoveDistance = 2.0,
                    onProgress = { progress ->
                        Message.announcement(player, progressBar10(progress))
                    },
                    onCancel = { reason ->
                        when ( reason ) {
                            TaskProgress.CancelReason.ITEM_CHANGED -> Message.announcement(player, "${ChatColor.RED}Item changed, spawn cancelled!")
                            TaskProgress.CancelReason.MOVED -> Message.announcement(player, "${ChatColor.RED}Moved too far, spawn cancelled!")
                            else -> {}
                        }
                    },
                    onFinish = {
                        finishQueue.add(SpawnVehicleFinish(
                            prototype,
                            location,
                            player,
                            item,
                            itemIdTag,
                        ))
                    },
                )
                taskSpawn.runTaskTimerAsynchronously(xv.plugin, 2, 2)
            }
        } catch ( err: Exception ) {
            err.printStackTrace()
        }
    }
}

public fun XV.systemFinishSpawnVehicle(
    requests: ConcurrentLinkedQueue<SpawnVehicleFinish>,
    createRequests: Queue<CreateVehicleRequest>,
) {
    val xv = this

    for ( request in requests.drain() ) {
        val (
            prototype,
            location,
            player,
            item,
            itemIdTag,
        ) = request

        try {
            // do final check that vehicle item matches item in player hand,
            // then consume vehicle item
            if ( player !== null && item !== null ) {
                if ( !player.itemInMainHandEquivalentTo(item, itemIdTag) ) {
                    Message.announcement(player, "${ChatColor.RED}Item changed, spawn cancelled!")
                    return
                }
                // else, passed check, consume item
                player.inventory.setItemInMainHand(null)
            }

            // push create request to create system
            createRequests.add(CreateVehicleRequest(
                prototype,
                location = location,
                player = player,
                item = item,
            ))

        } catch ( err: Exception ) {
            err.printStackTrace()
        }
    }
}

public fun XV.systemDespawnVehicle(
    requests: Queue<DespawnVehicleRequest>,
    finishQueue: ConcurrentLinkedQueue<DespawnVehicleFinish>,
) {
    val xv = this

    for ( request in requests.drain() ) {
        val (
            vehicle,
            player,
            dropItem,
            force,
            skipTimer,
        ) = request
        
        try {
            if ( player !== null ) {
                val taskDespawn = TaskProgress(
                    timeTaskMillis = 4000,
                    player = player,
                    maxMoveDistance = 2.0,
                    onProgress = { progress ->
                        Message.announcement(player, progressBar10(progress))
                    },
                    onCancel = { reason ->
                        when ( reason ) {
                            TaskProgress.CancelReason.MOVED -> Message.announcement(player, "${ChatColor.RED}Moved too far, spawn cancelled!")
                            else -> {}
                        }
                    },
                    onFinish = {
                        finishQueue.add(DespawnVehicleFinish(
                            vehicle,
                            player,
                            dropItem,
                            force,
                        ))
                    },
                )
                taskDespawn.runTaskTimerAsynchronously(xv.plugin, 2, 2)
            }
        } catch ( err: Exception ) {
            err.printStackTrace()
        }
    }
}

/**
 * Handle despawning vehicle requests.
 * Actual deletion of vehicle and its elements is handled by the destroy
 * system.
 */
public fun XV.systemFinishDespawnVehicle(
    requests: ConcurrentLinkedQueue<DespawnVehicleFinish>,
    deleteRequests: Queue<DestroyVehicleRequest>,
) {
    val xv = this

    for ( request in requests.drain() ) {
        val (
            vehicle,
            player,
            dropItem,
            force,
        ) = request

        // create request to destroy vehicle
        deleteRequests.add(DestroyVehicleRequest(vehicle))

        try {
            // drop item if vehicle has valid position in world
            if ( dropItem ) {
                // search elements for first element with transform component
                // -> use that element as vehicle "location"
                var location: Location? = null
                for ( element in vehicle.elements ) {
                    if ( element.layout.contains(VehicleComponentType.TRANSFORM) ) {
                        val transform = element.components.transform!!
                        val world = transform.world
                        if ( world !== null ) {
                            // add some y offset so that item drops from near middle of vehicle
                            location = Location(world, transform.x, transform.y + 1.0, transform.z)
                        }
                        break
                    }
                }

                if ( location !== null ) {
                    val item = vehicle.prototype.toItemStack(
                        xv.config.materialVehicle,
                        vehicle.elements,
                    )
                    if ( item !== null ) {
                        location.world.dropItem(location, item)
                    }
                }
            } else {
                null
            }
        } catch ( err: Exception ) {
            err.printStackTrace()
        }
    }
}

