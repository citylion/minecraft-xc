package phonon.xv.nms

import net.minecraft.server.level.ServerPlayer
import net.minecraft.nbt.Tag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.IntTag
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.entity.player.Player
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.util.CraftMagicNumbers

internal typealias NmsItem = Item
internal typealias NmsItemStack = ItemStack
internal typealias NmsNBTTagCompound = CompoundTag
internal typealias NmsNBTTagList = ListTag
internal typealias NmsNBTTagString = StringTag
internal typealias NmsNBTTagInt = IntTag
internal typealias NmsPacketPlayOutSetSlot = ClientboundContainerSetSlotPacket
internal typealias NmsPlayer = Player


// ============================================================================
// EXTENSION FUNCTIONS FOR API COMPATIBILITY
// ============================================================================


/**
 * Compatibility wrapper for setting NBT tag in compound tag.
 * Using "putTag" because actual function name "put" in 1.18.2 is
 * too common.
 */
internal fun CompoundTag.putTag(key: String, tag: Tag) {
    this.put(key, tag)
}

/**
 * Compatibility wrapper for compound tag "hasKey" in older versions.
 */
internal fun CompoundTag.containsKey(key: String): Boolean {
    return this.contains(key)
}

/**
 * Compatibility wrapper for compound tag "hasKeyOfType" in older versions.
 */
internal fun CompoundTag.containsKeyOfType(key: String, ty: Int): Boolean {
    return this.contains(key, ty)
}

// ============================================================================
// NBT TAG OBFUSCATED FUNCTION WRAPPERS
// Problem: NBT tags created using static functions...cannot write extension
// function version compatibility wrappers for static functions... :^(
// So instead use a value class wrapper.
// ============================================================================

@JvmInline
internal value class NBTTagString(val tag: NmsNBTTagString) {
    constructor(s: String) : this(NmsNBTTagString.valueOf(s))

    fun toNms(): NmsNBTTagString {
        return this.tag
    }
}

@JvmInline
internal value class NBTTagInt(val tag: NmsNBTTagInt) {
    constructor(i: Int) : this(NmsNBTTagInt.valueOf(i))

    fun toNms(): NmsNBTTagInt {
        return this.tag
    }
}

/**
 * Get player selected item as an NMS ItemStack. 
*/
internal fun CraftPlayer.getMainHandNMSItem(): NmsItemStack {
    val nmsPlayer = this.getHandle()
    return nmsPlayer.getMainHandItem()
}
