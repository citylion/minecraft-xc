/**
 * FILE IS GENERATED BY CODEGEN SCRIPT, WHICH IMPLEMENTS ALL 
 * COMPONENT TYPES. DO NOT EDIT THIS FILE DIRECTLY.
 * 
 * Archetype style ECS data storage core.
 * 
 * Contains vehicle component enum + interface and archetype storage.
 * 
 * Since component set is finite, just hard-code optional component
 * storages in each archetype. Engine must ensure we only access
 * valid storages in the archetype
 * 
 * See references:
 * https://github.com/amethyst/legion/blob/master/src/internals/storage/archetype.rs
 */

package phonon.xv.core

import java.util.EnumSet
import phonon.xv.core.INVALID_ELEMENT_ID
import com.google.gson.JsonObject
import phonon.xv.component.*
import java.util.Stack

public const val INVALID_DENSE_INDEX: Int = -1

/**
 * Note: keep in alphabetical order.
 */
public enum class VehicleComponentType {
    FUEL,
    GUN_TURRET,
    HEALTH,
    LAND_MOVEMENT_CONTROLS,
    MODEL,
    SEATS,
    SEATS_RAYCAST,
    TRANSFORM,
    ;

    public companion object {
        /**
         * Converts from compile-time generic vehicle component type. 
         */
        public inline fun <reified T: VehicleComponent<T>> from(): VehicleComponentType {
            return when ( T::class ) {
                FuelComponent::class -> VehicleComponentType.FUEL
                GunTurretComponent::class -> VehicleComponentType.GUN_TURRET
                HealthComponent::class -> VehicleComponentType.HEALTH
                LandMovementControlsComponent::class -> VehicleComponentType.LAND_MOVEMENT_CONTROLS
                ModelComponent::class -> VehicleComponentType.MODEL
                SeatsComponent::class -> VehicleComponentType.SEATS
                SeatsRaycastComponent::class -> VehicleComponentType.SEATS_RAYCAST
                TransformComponent::class -> VehicleComponentType.TRANSFORM
                else -> throw Exception("Unknown component type")
            }
        }
    }
}

/**
 * Archetype, contains set of possible component storages.
 * Components stored in packed struct-of-arrays format.
 * Element Id used to lookup packed index.
 */
public class ArchetypeStorage(
    val layout: EnumSet<VehicleComponentType>,
    val maxElements: Int,
) {
    public var size: Int = 0
        // private set // TODO: when implemented, outside should never change size

    // fluffy start, vehicle element id manager + dense map/array

    // element id => element
    private val lookup: HashMap<VehicleElementId, VehicleElement> = HashMap()
    // stack of free ids that are not at the end of the lookup array
    // lookup array vehicle element id => dense array index
    // the sets we're mapping have equal cardinality, we use a dense map
    // to keep all components in contiguous block in dense array
    val denseLookup: Array<Int> = Array(maxElements) { _ -> INVALID_DENSE_INDEX }

    // dense array index => vehicle element id
    // only internal cuz iterator classes need it
    internal val elements: IntArray = IntArray(maxElements) { _ -> INVALID_ELEMENT_ID }
    // denseLookup implicit linked list head
    internal var freedNext: Int = 0

    /*
    // map from vehicle element id => element's dense array index
    // TODO: replace with specialized Densemap
    public val lookup: HashMap<VehicleElementId, Int> = HashMap()

    // dense packed element ids
    public val elements: IntArray = IntArray(maxElements, {_ -> INVALID_ELEMENT_ID})
    */

    // dense packed components
    // only components in layout will be non-null
    public val fuel: ArrayList<FuelComponent>? = if ( layout.contains(VehicleComponentType.FUEL) ) ArrayList() else null
    public val gunTurret: ArrayList<GunTurretComponent>? = if ( layout.contains(VehicleComponentType.GUN_TURRET) ) ArrayList() else null
    public val health: ArrayList<HealthComponent>? = if ( layout.contains(VehicleComponentType.HEALTH) ) ArrayList() else null
    public val landMovementControls: ArrayList<LandMovementControlsComponent>? = if ( layout.contains(VehicleComponentType.LAND_MOVEMENT_CONTROLS) ) ArrayList() else null
    public val model: ArrayList<ModelComponent>? = if ( layout.contains(VehicleComponentType.MODEL) ) ArrayList() else null
    public val seats: ArrayList<SeatsComponent>? = if ( layout.contains(VehicleComponentType.SEATS) ) ArrayList() else null
    public val seatsRaycast: ArrayList<SeatsRaycastComponent>? = if ( layout.contains(VehicleComponentType.SEATS_RAYCAST) ) ArrayList() else null
    public val transform: ArrayList<TransformComponent>? = if ( layout.contains(VehicleComponentType.TRANSFORM) ) ArrayList() else null

    // element id => element lookup, function for type safety
    fun lookup(id: VehicleElementId): VehicleElement? {
        return lookup[id]
    }

    inline fun <reified T: VehicleComponent<T>> getComponent(id: VehicleElementId): T? {
        val denseIndex = this.denseLookup[id]
        return when ( T::class ) {
            FuelComponent::class -> this.fuel?.get(denseIndex) as T
            GunTurretComponent::class -> this.gunTurret?.get(denseIndex) as T
            HealthComponent::class -> this.health?.get(denseIndex) as T
            LandMovementControlsComponent::class -> this.landMovementControls?.get(denseIndex) as T
            ModelComponent::class -> this.model?.get(denseIndex) as T
            SeatsComponent::class -> this.seats?.get(denseIndex) as T
            SeatsRaycastComponent::class -> this.seatsRaycast?.get(denseIndex) as T
            TransformComponent::class -> this.transform?.get(denseIndex) as T
            else -> throw Exception("Unknown component type.")
        }
    }

    // reserves a new element id and internally adds an entry in dense array
    // YOU NEED TO UPDATE THE LOOKUP MAP YOURSELF!
    public fun newId(): VehicleElementId {
        if ( size >= MAX_VEHICLE_ELEMENTS )
            return INVALID_ELEMENT_ID
        // new id is head of linked list
        val newId = freedNext
        // update dense array
        elements[size] = newId
        // set new head of implicit linked list
        freedNext = denseLookup[freedNext]
        if ( freedNext == -1 ) {
            freedNext = size + 1
        }
        // update dense lookup
        denseLookup[newId] = size
        size++
        return newId
    }

    // inject the vehicle element w/ its component data
    // into the archetype storage, this is assuming we've
    // already called newId() to reserve its id
    public fun inject(
        element: VehicleElement,
        fuel: FuelComponent?,
        gunTurret: GunTurretComponent?,
        health: HealthComponent?,
        landMovementControls: LandMovementControlsComponent?,
        model: ModelComponent?,
        seats: SeatsComponent?,
        seatsRaycast: SeatsRaycastComponent?,
        transform: TransformComponent?,
    ) {
        this.lookup[element.id] = element
        val denseIndex = denseLookup[element.id]
        if ( fuel != null ) {
            val storageSize = this.fuel!!.size
            if (storageSize == denseIndex) {
                this.fuel.add(fuel)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.fuel.set(denseIndex, fuel)
            }
        }
        if ( gunTurret != null ) {
            val storageSize = this.gunTurret!!.size
            if (storageSize == denseIndex) {
                this.gunTurret.add(gunTurret)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.gunTurret.set(denseIndex, gunTurret)
            }
        }
        if ( health != null ) {
            val storageSize = this.health!!.size
            if (storageSize == denseIndex) {
                this.health.add(health)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.health.set(denseIndex, health)
            }
        }
        if ( landMovementControls != null ) {
            val storageSize = this.landMovementControls!!.size
            if (storageSize == denseIndex) {
                this.landMovementControls.add(landMovementControls)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.landMovementControls.set(denseIndex, landMovementControls)
            }
        }
        if ( model != null ) {
            val storageSize = this.model!!.size
            if (storageSize == denseIndex) {
                this.model.add(model)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.model.set(denseIndex, model)
            }
        }
        if ( seats != null ) {
            val storageSize = this.seats!!.size
            if (storageSize == denseIndex) {
                this.seats.add(seats)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.seats.set(denseIndex, seats)
            }
        }
        if ( seatsRaycast != null ) {
            val storageSize = this.seatsRaycast!!.size
            if (storageSize == denseIndex) {
                this.seatsRaycast.add(seatsRaycast)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.seatsRaycast.set(denseIndex, seatsRaycast)
            }
        }
        if ( transform != null ) {
            val storageSize = this.transform!!.size
            if (storageSize == denseIndex) {
                this.transform.add(transform)
            } else if (storageSize < denseIndex) {
                throw IllegalStateException("Archetype storage attempted to insert an element at a dense index larger than current size. index: ${denseIndex}")
            } else {
                this.transform.set(denseIndex, transform)
            }
        }
    }

    // mark id as deleted
    public fun freeId(id: VehicleElementId) {
        lookup.remove(id)
        // index in dense array to delete
        val index = denseLookup[id]
        // swap values in dense array w/ last elt
        val idAtLast = elements[size - 1]
        elements[index] = idAtLast
        elements[size - 1] = -1
        // update in dense lookup and implicit list head
        denseLookup[id] = freedNext
        freedNext = id
        denseLookup[idAtLast] = index
        // make the swap in component arrays
        fuel!!.set(index, fuel.get(size - 1))
        fuel.removeAt(size - 1)
        gunTurret!!.set(index, gunTurret.get(size - 1))
        gunTurret.removeAt(size - 1)
        health!!.set(index, health.get(size - 1))
        health.removeAt(size - 1)
        landMovementControls!!.set(index, landMovementControls.get(size - 1))
        landMovementControls.removeAt(size - 1)
        model!!.set(index, model.get(size - 1))
        model.removeAt(size - 1)
        seats!!.set(index, seats.get(size - 1))
        seats.removeAt(size - 1)
        seatsRaycast!!.set(index, seatsRaycast.get(size - 1))
        seatsRaycast.removeAt(size - 1)
        transform!!.set(index, transform.get(size - 1))
        transform.removeAt(size - 1)
        size--
    }

    // fluffy end
    
    /**
     * Remove all elements from archetype.
     */
    public fun clear() {
        size = 0
        lookup.clear()
        fuel?.clear()
        gunTurret?.clear()
        health?.clear()
        landMovementControls?.clear()
        model?.clear()
        seats?.clear()
        seatsRaycast?.clear()
        transform?.clear()
    }

    public companion object {
        /**
         * Higher order function that returns a function that gets a
         * Vehicle component type's storage within the archetype.
         * Needed to allows compile-time access to a type's component
         * storage in the archetype. Used for generic tuple iterators.
         * 
         * Internally does unsafe cast, since storages may be null.
         * Client caller must make sure archetypes have the storages.
         * 
         * Note: this may be generating a new lambda object at each call.
         * May want to cache the lambda object, or hard-code each function
         * in future.
         */
        @Suppress("UNCHECKED_CAST")
        public inline fun <reified T> accessor(): (ArchetypeStorage) -> ArrayList<T> {
            return when ( T::class ) {
                FuelComponent::class -> { archetype -> archetype.fuel as ArrayList<T> }
                GunTurretComponent::class -> { archetype -> archetype.gunTurret as ArrayList<T> }
                HealthComponent::class -> { archetype -> archetype.health as ArrayList<T> }
                LandMovementControlsComponent::class -> { archetype -> archetype.landMovementControls as ArrayList<T> }
                ModelComponent::class -> { archetype -> archetype.model as ArrayList<T> }
                SeatsComponent::class -> { archetype -> archetype.seats as ArrayList<T> }
                SeatsRaycastComponent::class -> { archetype -> archetype.seatsRaycast as ArrayList<T> }
                TransformComponent::class -> { archetype -> archetype.transform as ArrayList<T> }
                else -> throw Exception("Unknown component type")
            }
        }
    }
}