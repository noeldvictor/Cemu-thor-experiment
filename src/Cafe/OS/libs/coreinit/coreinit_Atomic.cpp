#include "Cafe/OS/common/OSCommon.h"
#include "Cafe/HW/MMU/MMU.h"
#include <atomic>
#include <mutex>
#include "coreinit_Atomic.h"

namespace coreinit
{
	namespace
	{
		std::mutex s_atomicLock;

		MPTR atomicPtrToMPTR(const void* mem)
		{
			return memory_getVirtualOffsetFromPointer(const_cast<void*>(mem));
		}
	}

	/* 32bit atomic operations */

	uint32 OSSwapAtomic(std::atomic<uint32be>* mem, uint32 newValue)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint32 previousValue = memory_readU32(memMPTR);
		memory_writeU32(memMPTR, newValue);
		return previousValue;
	}

	bool OSCompareAndSwapAtomic(std::atomic<uint32be>* mem, uint32 compareValue, uint32 swapValue)
	{
		// seen in GTA3 homebrew port
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint32 previousValue = memory_readU32(memMPTR);
		if (previousValue != compareValue)
			return false;
		memory_writeU32(memMPTR, swapValue);
		return true;
	}

	bool OSCompareAndSwapAtomicEx(std::atomic<uint32be>* mem, uint32 compareValue, uint32 swapValue, uint32be* previousValue)
	{
		// seen in GTA3 homebrew port
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint32 currentValue = memory_readU32(memMPTR);
		const bool exchanged = currentValue == compareValue;
		if (exchanged)
			memory_writeU32(memMPTR, swapValue);
		if (previousValue)
			memory_writeU32(atomicPtrToMPTR(previousValue), currentValue);
		return exchanged;
	}

	uint32 OSAddAtomic(std::atomic<uint32be>* mem, uint32 adder)
	{
        // used by SDL Wii U port
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint32 previousValue = memory_readU32(memMPTR);
		memory_writeU32(memMPTR, previousValue + adder);
		return previousValue;
	}

	/* 64bit atomic operations */

	uint64 OSSwapAtomic64(std::atomic<uint64be>* mem, uint64 newValue)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint64 previousValue = memory_readU64(memMPTR);
		memory_writeU64(memMPTR, newValue);
		return previousValue;
	}

	uint64 OSSetAtomic64(std::atomic<uint64be>* mem, uint64 newValue)
	{
		return OSSwapAtomic64(mem, newValue);
	}

	uint64 OSGetAtomic64(std::atomic<uint64be>* mem)
	{
		std::lock_guard lock(s_atomicLock);
		return memory_readU64(atomicPtrToMPTR(mem));
	}

	uint64 OSAddAtomic64(std::atomic<uint64be>* mem, uint64 adder)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint64 previousValue = memory_readU64(memMPTR);
		memory_writeU64(memMPTR, previousValue + adder);
		return previousValue;
	}

	uint64 OSAndAtomic64(std::atomic<uint64be>* mem, uint64 val)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint64 previousValue = memory_readU64(memMPTR);
		memory_writeU64(memMPTR, previousValue & val);
		return previousValue;
	}

	uint64 OSOrAtomic64(std::atomic<uint64be>* mem, uint64 val)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint64 previousValue = memory_readU64(memMPTR);
		memory_writeU64(memMPTR, previousValue | val);
		return previousValue;
	}

	bool OSCompareAndSwapAtomic64(std::atomic<uint64be>* mem, uint64 compareValue, uint64 swapValue)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint64 previousValue = memory_readU64(memMPTR);
		if (previousValue != compareValue)
			return false;
		memory_writeU64(memMPTR, swapValue);
		return true;
	}

	bool OSCompareAndSwapAtomicEx64(std::atomic<uint64be>* mem, uint64 compareValue, uint64 swapValue, uint64be* previousValue)
	{
		std::lock_guard lock(s_atomicLock);
		const MPTR memMPTR = atomicPtrToMPTR(mem);
		const uint64 currentValue = memory_readU64(memMPTR);
		const bool exchanged = currentValue == compareValue;
		if (exchanged)
			memory_writeU64(memMPTR, swapValue);
		if (previousValue)
			memory_writeU64(atomicPtrToMPTR(previousValue), currentValue);
		return exchanged;
	}

	void InitializeAtomic()
	{
		// 32bit atomic operations
		cafeExportRegister("coreinit", OSSwapAtomic, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomic, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomicEx, LogType::Placeholder);
		cafeExportRegister("coreinit", OSAddAtomic, LogType::Placeholder);
		
		// 64bit atomic operations
		cafeExportRegister("coreinit", OSSetAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSGetAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSSwapAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSAddAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSAndAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSOrAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomic64, LogType::Placeholder);
		cafeExportRegister("coreinit", OSCompareAndSwapAtomicEx64, LogType::Placeholder);
	}
}
