package powercrystals.minefactoryreloaded.tile.rednet;

import cofh.api.tileentity.IPortableData;
import cofh.lib.util.position.BlockPosition;
import cofh.lib.util.position.IRotateableTile;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.ForgeDirection;

import powercrystals.minefactoryreloaded.api.rednet.IRedNetInputNode;
import powercrystals.minefactoryreloaded.api.rednet.IRedNetLogicCircuit;
import powercrystals.minefactoryreloaded.api.rednet.IRedNetNetworkContainer;
import powercrystals.minefactoryreloaded.api.rednet.connectivity.RedNetConnectionType;
import powercrystals.minefactoryreloaded.circuits.Noop;
import powercrystals.minefactoryreloaded.item.ItemLogicUpgradeCard;
import powercrystals.minefactoryreloaded.net.Packets;
import powercrystals.minefactoryreloaded.setup.MFRThings;
import powercrystals.minefactoryreloaded.tile.base.TileEntityBase;

public class TileEntityRedNetLogic extends TileEntityBase implements IRotateableTile, IPortableData {

	public static class PinMapping {

		public PinMapping(int pin, int buffer) {

			this.pin = pin;
			this.buffer = buffer;
		}

		public int pin;
		public int buffer;
	}

	private int _circuitCount = 6;

	private int _variableCount = 16;

	private IRedNetLogicCircuit[] _prevCircuits = new IRedNetLogicCircuit[_circuitCount];

	private IRedNetLogicCircuit[] _circuits = new IRedNetLogicCircuit[_circuitCount];
	private boolean[] _updatable = new boolean[_circuitCount];

	// 0-5 in, 6-11 out, 12 const, 13 var, 14 null
	private int[][] _buffers = new int[15][];
	private int[][] _backBuffer = new int[6][];

	private BlockPosition bp = new BlockPosition(0, 0, 0);

	private PinMapping[][] _pinMappingInputs = new PinMapping[_circuitCount][];
	private PinMapping[][] _pinMappingOutputs = new PinMapping[_circuitCount][];

	private int[] _upgradeLevel = new int[6];

	public int crafters = 0;

	public TileEntityRedNetLogic() {

		// init I/O buffers
		for (int i = 0; i < 12; i++) {
			_buffers[i] = new int[16];
		}

		for (int i = 0; i < 6; i++) {
			_backBuffer[i] = new int[16];
		}

		//init constants
		_buffers[12] = null;
		// init variable buffer
		_buffers[13] = new int[_variableCount];
		// init null buffer
		_buffers[14] = new int[1];

		// init circuits
		for (int i = 0; i < _circuits.length; i++) {
			initCircuit(i, new Noop());
		}
	}

	public int getVariableBufferSize() {

		return _variableCount;
	}

	public int getCircuitCount() {

		return _circuitCount;
	}

	public int getBufferLength(int buffer) {

		return _buffers[buffer].length;
	}

	public int getVariableValue(int var) {

		return _buffers[13][var];
	}

	public IRedNetLogicCircuit getCircuit(int index) {

		return _circuits[index];
	}

	@SideOnly(Side.CLIENT)
	public IRedNetLogicCircuit getLastCircuit(int index) {

		IRedNetLogicCircuit r = _prevCircuits[index];
		_prevCircuits[index] = _circuits[index];
		return r;
	}

	public PinMapping getInputPinMapping(int circuitIndex, int pinIndex) {

		return _pinMappingInputs[circuitIndex][pinIndex];
	}

	public void setInputPinMapping(int circuitIndex, int pinIndex, int buffer, int pin) {

		_pinMappingInputs[circuitIndex][pinIndex] = new PinMapping(pin, buffer);
	}

	public PinMapping getOutputPinMapping(int circuitIndex, int pinIndex) {

		return _pinMappingOutputs[circuitIndex][pinIndex];
	}

	public void setOutputPinMapping(int circuitIndex, int pinIndex, int buffer, int pin) {

		_pinMappingOutputs[circuitIndex][pinIndex] = new PinMapping(pin, buffer);
	}

	private IRedNetLogicCircuit getNewCircuit(String className) {

		try {
			return (IRedNetLogicCircuit) Class.forName(className).newInstance();
		} catch (Exception e) {
			e.printStackTrace();
			return new Noop();
		}
	}

	public void initCircuit(int index, String circuitClassName) {

		initCircuit(index, getNewCircuit(circuitClassName));
	}

	private void initCircuit(int index, IRedNetLogicCircuit circuit) {

		_updatable[index] = !(circuit instanceof Noop);
		_circuits[index] = circuit;
		if (_pinMappingInputs[index] == null) {
			_pinMappingInputs[index] = new PinMapping[_circuits[index].getInputCount()];
		} else {
			_pinMappingInputs[index] = Arrays.copyOf(_pinMappingInputs[index], _circuits[index].getInputCount());
		}

		if (_pinMappingOutputs[index] == null) {
			_pinMappingOutputs[index] = new PinMapping[_circuits[index].getOutputCount()];
		} else {
			_pinMappingOutputs[index] = Arrays.copyOf(_pinMappingOutputs[index], _circuits[index].getOutputCount());
		}

		for (int i = 0; i < _pinMappingInputs[index].length; i++) {
			if (_pinMappingInputs[index][i] == null) {
				_pinMappingInputs[index][i] = new PinMapping(0, 12);
			}
		}

		for (int i = 0; i < _pinMappingOutputs[index].length; i++) {
			if (_pinMappingOutputs[index][i] == null) {
				_pinMappingOutputs[index][i] = new PinMapping(0, 14);
			}
		}
	}

	public void reinitialize(EntityPlayer player) {

		for (int i = 0; i < _upgradeLevel.length; i++) {
			if (_upgradeLevel[i] > 0) {
				ItemStack card = new ItemStack(MFRThings.logicCardItem, 1, _upgradeLevel[i] - 1);
				if (!player.inventory.addItemStackToInventory(card)) {
					player.entityDropItem(card, 0.0F);
				}
				_upgradeLevel[i] = 0;
			}
		}
		updateUpgradeLevels();
		// init I/O buffers
		for (int i = 0; i < 12; i++) {
			_buffers[i] = new int[16];
		}

		for (int i = 0; i < 6; i++) {
			_backBuffer[i] = new int[16];
		}

		//init constants
		_buffers[12] = null;
		// init variable buffer
		_buffers[13] = new int[_variableCount];
		// init null buffer
		_buffers[14] = new int[1];

		// init circuits
		for (int i = 0; i < _circuits.length; i++) {
			initCircuit(i, new Noop());
			sendCircuitDefinition(i);
		}
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}

	public void setCircuitFromPacket(NBTTagCompound packet) {

		try {
			int circuitIndex = packet.getInteger("i");
			String circuitName = packet.getString("name");

			initCircuit(circuitIndex, circuitName);

			int len = packet.getByte("input");
			int[] inputs = packet.getIntArray("inputs");
			PinMapping[] pins = _pinMappingInputs[circuitIndex];
			for (int p = 0; p < len; ++p)
				pins[p] = new PinMapping(inputs[p << 1], inputs[(p << 1) | 1]);

			len = packet.getByte("output");
			inputs = packet.getIntArray("outputs");
			pins = _pinMappingOutputs[circuitIndex];
			for (int p = 0; p < len; ++p)
				pins[p] = new PinMapping(inputs[p << 1], inputs[(p << 1) | 1]);
		} catch (Throwable x) {
			x.printStackTrace();
		}
	}

	public void sendCircuitDefinition(int circuit) {

		NBTTagCompound data = new NBTTagCompound();

		data.setInteger("i", circuit);

		data.setString("name", _circuits[circuit].getClass().getName());

		byte len = _circuits[circuit].getInputCount();
		data.setByte("input", len);
		int[] l = new int[len * 2];
		PinMapping[] pins = _pinMappingInputs[circuit];
		for (int p = 0, i = 0; i < len; ++i) {
			l[p] = pins[i].pin;
			++p;
			l[p] = pins[i].buffer;
			++p;
		}
		data.setIntArray("inputs", l);

		len = _circuits[circuit].getOutputCount();
		data.setByte("output", len);
		l = new int[len * 2];
		pins = _pinMappingOutputs[circuit];
		for (int p = 0, i = 0; i < len; ++i) {
			l[p] = pins[i].pin;
			++p;
			l[p] = pins[i].buffer;
			++p;
		}
		data.setIntArray("outputs", l);

		Packets.sendToAllPlayersInRange(worldObj, xCoord, yCoord, zCoord, 10,
			new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, data));
	}

	@Override
	public void updateEntity() {

		if (worldObj.isRemote) {
			return;
		}

		for (int i = 0; i < 6; i++) {
			int[] buffer = _buffers[i + 6];
			int[] backbuffer = _backBuffer[i];
			for (int j = 16; j-- > 0;) {
				backbuffer[j] = buffer[j];
				buffer[j] = 0;
			}
		}

		for (int circuitNum = 0, e = _circuits.length; circuitNum < e; ++circuitNum) {
			if (_updatable[circuitNum]) {
				IRedNetLogicCircuit circuit = _circuits[circuitNum];
				PinMapping[] mappings = _pinMappingInputs[circuitNum];
				int[] input = new int[circuit.getInputCount()];
				for (int pinNum = 0, j = input.length; pinNum < j; ++pinNum) {
					PinMapping mapping = mappings[pinNum];
					input[pinNum] = mapping.buffer == 12 ? mapping.pin : _buffers[mapping.buffer][mapping.pin];
				}

				int[] output = circuit.recalculateOutputValues(worldObj.getTotalWorldTime(), input);
				mappings = _pinMappingOutputs[circuitNum];
				for (int pinNum = 0, j = output.length; pinNum < j; ++pinNum) {
					PinMapping mapping = mappings[pinNum];
					_buffers[mapping.buffer][mapping.pin] = output[pinNum];
				}
			}
		}

		BlockPosition bp = this.bp;
		for (int i = 0; i < 6; i++) {
			if (!areEqual(_backBuffer[i], _buffers[i + 6])) {
				bp.x = xCoord;
				bp.y = yCoord;
				bp.z = zCoord;
				ForgeDirection o = ForgeDirection.VALID_DIRECTIONS[i];
				bp.step(o);
				Block b = worldObj.getBlock(bp.x, bp.y, bp.z);
				if (b instanceof IRedNetNetworkContainer) {
					((IRedNetNetworkContainer) b).updateNetwork(worldObj, bp.x, bp.y, bp.z, o.getOpposite());
				} else if (b instanceof IRedNetInputNode) {
					IRedNetInputNode n = ((IRedNetInputNode) b);
					RedNetConnectionType type = n.getConnectionType(worldObj, bp.x, bp.y, bp.z, o.getOpposite());
					if (type.isConnected) {
						if (type.isAllSubnets)
							n.onInputsChanged(worldObj, bp.x, bp.y, bp.z, o.getOpposite(), _buffers[i + 6]);
						else if (type.isSingleSubnet)
							n.onInputChanged(worldObj, bp.x, bp.y, bp.z, o.getOpposite(), _buffers[i + 6][0]);
					}
				}
			}
		}
		markChunkDirty();
	}

	public int getOutputValue(ForgeDirection side, int subnet) {

		if (side == ForgeDirection.UNKNOWN) {
			return 0;
		}
		return _buffers[side.ordinal() + 6][subnet];
	}

	public int[] getOutputValues(ForgeDirection side) {

		if (side == ForgeDirection.UNKNOWN) {
			return new int[16];
		}
		return _buffers[side.ordinal() + 6];
	}

	public void onInputsChanged(ForgeDirection side, int[] values) {

		if (side != ForgeDirection.UNKNOWN) {
			_buffers[side.ordinal()] = values;
		}
	}

	@Override
	public String getDataType() {

		return "tile.mfr.rednet.logic.name";
	}

	@Override
	public void writePortableData(EntityPlayer player, NBTTagCompound tag) {

		writeCricuitsOnly(tag, false);
		if (tag.hasKey("circuits", 9)) {
			tag.setByte("p_rot", (byte) worldObj.getBlockMetadata(xCoord, yCoord, zCoord));
			player.addChatMessage(new ChatComponentTranslation("chat.info.mfr.rednet.memorycard.uploaded"));
		} else {
			player.addChatMessage(new ChatComponentTranslation("chat.info.mfr.rednet.memorycard.empty"));
		}
	}

	@Override
	public void readPortableData(EntityPlayer player, NBTTagCompound tag) {

		if (!canRotate()) {
			player.addChatMessage(new ChatComponentTranslation("chat.info.mfr.rednet.memorycard.error2"));
			return;
		}
		int circuitCount = tag.getTagList("circuits", 10).tagCount();
		if (circuitCount > getCircuitCount()) {
			player.addChatMessage(new ChatComponentTranslation("chat.info.mfr.rednet.memorycard.error"));
		} else {
			readCircuitsOnly(tag);
			player.addChatMessage(new ChatComponentTranslation("chat.info.mfr.rednet.memorycard.downloaded"));
		}
	}

	@Override
	public void writeItemNBT(NBTTagCompound tag) {

		super.writeItemNBT(tag);
		writeCricuitsOnly(tag, true);
		if (tag.hasKey("circuits", 9))
			tag.setByte("p_rot", (byte) worldObj.getBlockMetadata(xCoord, yCoord, zCoord));
		l: for (int v : _upgradeLevel)
			if (v != 0) {
				tag.setIntArray("upgrades", _upgradeLevel);
				break l;
			}
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {

		super.writeToNBT(tag);

		tag.setByte("p_rot", (byte) -1);
		tag.setIntArray("vars", _buffers[13]);
	}

	public void writeCricuitsOnly(NBTTagCompound tag, boolean includeNoop) {

		NBTTagList circuits = new NBTTagList();
		for (int c = 0; c < _circuits.length; c++) {
			if (_circuits[c] instanceof Noop) {
				continue;
			}
			NBTTagCompound circuit = new NBTTagCompound();
			circuit.setString("circuit", _circuits[c].getClass().getName());
			if (includeNoop) {
				circuit.setInteger("index", c);
			}

			NBTTagList inputPins = new NBTTagList();
			for (int p = 0; p < _pinMappingInputs[c].length; p++) {
				NBTTagCompound pin = new NBTTagCompound();
				pin.setInteger("buffer", _pinMappingInputs[c][p].buffer);
				pin.setInteger("pin", _pinMappingInputs[c][p].pin);

				inputPins.appendTag(pin);
			}
			if (inputPins.tagCount() > 0)
				circuit.setTag("inputPins", inputPins);

			NBTTagList outputPins = new NBTTagList();
			for (int p = 0; p < _pinMappingOutputs[c].length; p++) {
				NBTTagCompound pin = new NBTTagCompound();
				pin.setInteger("buffer", _pinMappingOutputs[c][p].buffer);
				pin.setInteger("pin", _pinMappingOutputs[c][p].pin);

				outputPins.appendTag(pin);
			}
			if (outputPins.tagCount() > 0)
				circuit.setTag("outputPins", outputPins);

			NBTTagCompound circuitState = new NBTTagCompound();
			_circuits[c].writeToNBT(circuitState);
			if (!circuitState.hasNoTags())
				circuit.setTag("state", circuitState);

			circuits.appendTag(circuit);
		}

		if (circuits.tagCount() > 0)
			tag.setTag("circuits", circuits);
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {

		super.readFromNBT(tag);

		int[] upgrades = tag.getIntArray("upgrades");
		if (upgrades != null && upgrades.length == _upgradeLevel.length) {
			_upgradeLevel = upgrades;
		}
		updateUpgradeLevels();

		int[] vars = tag.getIntArray("vars");
		if (vars != null && vars.length == _buffers[13].length) {
			_buffers[13] = vars;
		}

		readCircuitsOnly(tag);
	}

	public void readCircuitsOnly(NBTTagCompound tag) {

		NBTTagList circuits = tag.getTagList("circuits", 10);
		int rot = tag.hasKey("p_rot") ? tag.getByte("p_rot") : -1, worldRot = 0;
		int[] map = {0, 1, 2, 3};
		if (rot != -1) {
			worldRot = worldObj.getBlockMetadata(xCoord, yCoord, zCoord) & 3;
			int[][] data = {{0,1,2,3},{3,2,0,1},{1,0,3,2},{2,3,1,0}};
			int i = worldRot - rot;
			if (i < 0) i += 4;
			map = data[i];
		}
		if (circuits != null) {
			int c = 0, e = circuits.tagCount();
			Arrays.fill(_circuits, null);
			for (; c < e; c++) {
				NBTTagCompound circuit = circuits.getCompoundTagAt(c);
				int i = circuit.hasKey("index") ? circuit.getInteger("index") : c;
				initCircuit(i, circuit.getString("circuit"));

				NBTTagList inputPins = circuit.getTagList("inputPins", 10);
				if (inputPins != null) {
					for (int k = 0; k < inputPins.tagCount() && k < _pinMappingInputs[c].length; k++) {
						NBTTagCompound pin = inputPins.getCompoundTagAt(k);
						int ipin = pin.getInteger("pin");
						int buffer = pin.getInteger("buffer");
						_pinMappingInputs[i][k] = new PinMapping(ipin, mapBuffer(map, buffer));
					}
				}

				NBTTagList outputPins = circuit.getTagList("outputPins", 10);
				if (outputPins != null) {
					for (int k = 0; k < outputPins.tagCount() && k < _pinMappingOutputs[c].length; k++) {
						NBTTagCompound pin = outputPins.getCompoundTagAt(k);
						int ipin = pin.getInteger("pin");
						int buffer = pin.getInteger("buffer");
						_pinMappingOutputs[i][k] = new PinMapping(ipin, mapBuffer(map, buffer));
					}
				}

				NBTTagCompound circuitState = circuit.getCompoundTag("state");
				if (circuitState != null) {
					_circuits[i].readFromNBT(circuitState);
				}
			}
			e = _circuits.length;
			for (c = 0; c < e; c++) {
				if (_circuits[c] == null)
					initCircuit(c, new Noop());
			}
		}
	}

	private int mapBuffer(int[] map, int buffer) {
		if (buffer > 1 && buffer < 6) {
			buffer = 2 + map[(buffer - 2) & 3];
		} else if (buffer > 7 && buffer < 12) {
			buffer = 8 + map[(buffer - 8) & 3];
		}
		return buffer;
	}

	@Override
	public Packet getDescriptionPacket() {

		NBTTagCompound data = new NBTTagCompound();
		data.setIntArray("upgrades", _upgradeLevel);
		S35PacketUpdateTileEntity packet = new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, data);
		return packet;
	}

	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {

		switch (pkt.func_148853_f()) {
		case 0:
			_upgradeLevel = pkt.func_148857_g().getIntArray("upgrades");
			updateUpgradeLevels();
			_prevCircuits = Arrays.copyOf(_prevCircuits, _circuitCount);
			break;
		case 1:
			setCircuitFromPacket(pkt.func_148857_g());
			break;
		}
	}

	public boolean insertUpgrade(int level) {

		for (int i = 0; i < 6; i++) {
			if (_upgradeLevel[i] == 0) {
				if (!worldObj.isRemote) {
					_upgradeLevel[i] = level;
					updateUpgradeLevels();
				}
				worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
				return true;
			}
		}
		return false;
	}

	public void setUpgrade(int slot, int level) {

		_upgradeLevel[slot] = level;
	}

	public int getLevelForSlot(int slot) {

		return _upgradeLevel[slot];
	}

	private void updateUpgradeLevels() {

		// recalculate sizes
		int circuitCount = 6;
		int variableCount = 16;
		for (int i = 0; i < _upgradeLevel.length; i++) {
			circuitCount += ItemLogicUpgradeCard.getCircuitsForLevel(_upgradeLevel[i]);
			variableCount += ItemLogicUpgradeCard.getVariablesForLevel(_upgradeLevel[i]);
		}

		_circuitCount = circuitCount;
		_variableCount = variableCount;

		// re-init circuit array and variable buffer
		_circuits = Arrays.copyOf(_circuits, _circuitCount);
		_updatable = Arrays.copyOf(_updatable, _circuitCount);
		_buffers[13] = Arrays.copyOf(_buffers[13], _variableCount);

		// re-init pinmapping arrays
		PinMapping[][] inputMappings = new PinMapping[_circuitCount][];
		for (int i = 0; i < inputMappings.length; i++) {
			if (i < _pinMappingInputs.length && _pinMappingInputs[i] != null) {
				inputMappings[i] = _pinMappingInputs[i];
			}
		}
		_pinMappingInputs = inputMappings;

		PinMapping[][] outputMappings = new PinMapping[_circuitCount][];
		for (int i = 0; i < outputMappings.length; i++) {
			if (i < _pinMappingOutputs.length && _pinMappingOutputs[i] != null) {
				outputMappings[i] = _pinMappingOutputs[i];
			}
		}
		_pinMappingOutputs = outputMappings;

		// finally, init any new circuits
		for (int i = 0; i < _circuits.length; i++) {
			if (_circuits[i] == null) {
				initCircuit(i, new Noop());
			}
		}
	}

	@Override
	public boolean canRotate() {

		return crafters == 0;
	}

	@Override
	public boolean canRotate(ForgeDirection axis) {

		return crafters == 0;
	}

	@Override
	public void rotate(ForgeDirection axis) {

		if (canRotate(axis)) {
			int currentMeta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
			int nextMeta = (currentMeta + 1) & 3; // % 4
			int[] map = {0, 1, 2, 3};
			{
				int[][] data = {{0,1,2,3},{3,2,0,1},{1,0,3,2},{2,3,1,0}};
				int i = nextMeta - currentMeta;
				if (i < 0) i += 4;
				map = data[i];
			}
			for (PinMapping[][] buffer : new PinMapping[][][]{_pinMappingInputs, _pinMappingOutputs}) {
				for (PinMapping[] mapping : buffer) {
					for (PinMapping pin : mapping) {
						pin.buffer = mapBuffer(map, pin.buffer);
					}
				}
			}
			worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, nextMeta, 3);
		}
	}

	@Override
	public void rotateDirectlyTo(int facing) {

		if (canRotate() && facing >= 2 && facing < 6) {
			worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, facing - 2, 3);
		}
	}

	@Override
	public ForgeDirection getDirectionFacing() {

		int meta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord) & 3;
		return ForgeDirection.getOrientation(meta + 2);
	}

	private static boolean areEqual(int[] a, int[] b) {

		if ((a == null | b == null) ||
				a.length != b.length)
			return false;

		//if (a == b) return true;

		for (int i = a.length; i-- > 0;)
			if (a[i] != b[i])
				return false;

		return true;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared()
	{

		return 4096.0D;
	}
}
