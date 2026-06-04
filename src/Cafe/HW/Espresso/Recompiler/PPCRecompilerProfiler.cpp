#include "Common/precompiled.h"
#include "PPCRecompilerProfiler.h"
#include "Cafe/HW/MMU/MMU.h"
#include "Cafe/HW/Espresso/PPCState.h"
#include "Cafe/OS/RPL/rpl.h"
#include "Cafe/OS/RPL/rpl_structs.h"
#include "config/ActiveSettings.h"
#include "util/helpers/helpers.h"

namespace PPCRecompilerProfiler
{
	alignas(4) uint32 g_hotBlockProfilerEnabled = 0;
	alignas(4) uint32 g_hotBlockProfilerSampleCounter = 0;
}

namespace
{
	constexpr size_t kHotBlockTableSize = 32768;
	constexpr size_t kHotBlockProbeCount = 8;
	constexpr uint64 kHotBlockSampleRate = 64;

	struct HotBlockEntry
	{
		std::atomic<uint32> addressKey{};
		std::atomic<uint64> hits{};
	};

	std::array<HotBlockEntry, kHotBlockTableSize> s_hotBlocks{};
	std::atomic<uint64> s_totalSamples{};
	std::atomic<uint64> s_overflowSamples{};

	uint32 MakeAddressKey(uint32 ppcAddress)
	{
		uint32 key = ppcAddress + 1;
		return key == 0 ? 1 : key;
	}

	uint32 AddressFromKey(uint32 key)
	{
		return key == 1 ? 0 : key - 1;
	}

	uint32 HashAddress(uint32 ppcAddress)
	{
		uint32 value = ppcAddress >> 2;
		value ^= value >> 16;
		value *= 0x7feb352d;
		value ^= value >> 15;
		value *= 0x846ca68b;
		value ^= value >> 16;
		return value;
	}

	std::atomic_ref<uint32> EnabledFlag()
	{
		return std::atomic_ref<uint32>(PPCRecompilerProfiler::g_hotBlockProfilerEnabled);
	}

	std::string BuildTimestamp()
	{
		const auto now = std::chrono::system_clock::now();
		const auto nowTime = std::chrono::system_clock::to_time_t(now);
		std::tm localTime{};
#if BOOST_OS_WINDOWS
		localtime_s(&localTime, &nowTime);
#else
		localtime_r(&nowTime, &localTime);
#endif
		return fmt::format("{:04}{:02}{:02}_{:02}{:02}{:02}",
			localTime.tm_year + 1900,
			localTime.tm_mon + 1,
			localTime.tm_mday,
			localTime.tm_hour,
			localTime.tm_min,
			localTime.tm_sec);
	}

	std::pair<std::string, uint32> GetModuleAndOffset(uint32 address)
	{
		RPLModule* module = RPLLoader_FindModuleByCodeAddr(address);
		if (!module)
			return { "unknown", 0 };

		return {
			module->moduleName.empty() ? std::string("unnamed") : module->moduleName,
			address - module->regionMappingBase_text.GetMPTR(),
		};
	}

	std::string FormatOpcodeWindow(uint32 address)
	{
		if (!memory_isAddressRangeAccessible(address, 16))
			return "unmapped";

		return fmt::format("{:08x} {:08x} {:08x} {:08x}",
			memory_readU32(address),
			memory_readU32(address + 4),
			memory_readU32(address + 8),
			memory_readU32(address + 12));
	}

	bool IsHLEOpcode(uint32 opcode)
	{
		return (opcode & 0xFC000000) == 0x04000000;
	}

	std::string FormatHLEOpcode(uint32 opcode)
	{
		const uint32 hleFuncId = opcode & 0xFFFF;
		if (hleFuncId == 0xFFD0)
			return "unsupported";

		const std::string_view hleName = PPCInterpreter_getHLECallName((HLEIDX)hleFuncId);
		if (!hleName.empty())
			return fmt::format("{}(0x{:04x})", hleName, hleFuncId);

		return fmt::format("hle#0x{:04x}", hleFuncId);
	}

	std::string FormatBlockDetails(uint32 address)
	{
		if (!memory_isAddressRangeAccessible(address, 16))
			return "-";

		std::vector<std::string> hleCalls;
		hleCalls.reserve(4);
		for (uint32 offset = 0; offset < 16; offset += 4)
		{
			const uint32 opcode = memory_readU32(address + offset);
			if (!IsHLEOpcode(opcode))
				continue;
			hleCalls.emplace_back(FormatHLEOpcode(opcode));
		}

		if (hleCalls.empty())
			return "-";

		return fmt::format("hle={}", fmt::join(hleCalls, ","));
	}

	void WriteModuleMap(std::ofstream& file)
	{
		const sint32 moduleCount = RPLLoader_GetModuleCount();
		RPLModule** moduleList = RPLLoader_GetModuleList();

		fmt::println(file, "# module_map:");
		for (sint32 i = 0; i < moduleCount; ++i)
		{
			RPLModule* module = moduleList[i];
			if (!module)
				continue;

			fmt::println(file,
				"# module {}\tbase 0x{:08x}\tsize 0x{:x}\tcrc 0x{:08x}\ttrampoline_adjust 0x{:x}",
				module->moduleName,
				module->regionMappingBase_text.GetMPTR(),
				module->regionSize_text,
				module->patchCRC,
				module->fileInfo.trampolineAdjustment);
		}
		fmt::println(file, "#");
	}
}

void PPCRecompilerProfiler::SetHotBlockProfilerEnabled(bool enabled)
{
	EnabledFlag().store(enabled ? 1u : 0u, std::memory_order_release);
}

bool PPCRecompilerProfiler::IsHotBlockProfilerEnabled()
{
	return EnabledFlag().load(std::memory_order_acquire) != 0;
}

void PPCRecompilerProfiler::ResetHotBlockProfiler()
{
	SetHotBlockProfilerEnabled(false);
	g_hotBlockProfilerSampleCounter = 0;
	for (auto& entry : s_hotBlocks)
	{
		entry.hits.store(0, std::memory_order_relaxed);
		entry.addressKey.store(0, std::memory_order_relaxed);
	}
	s_totalSamples.store(0, std::memory_order_relaxed);
	s_overflowSamples.store(0, std::memory_order_relaxed);
}

std::string PPCRecompilerProfiler::DumpHotBlockProfiler()
{
	struct SnapshotEntry
	{
		uint32 address;
		uint64 hits;
	};

	std::vector<SnapshotEntry> entries;
	entries.reserve(kHotBlockTableSize);

	for (auto& entry : s_hotBlocks)
	{
		const uint32 key = entry.addressKey.load(std::memory_order_relaxed);
		if (key == 0)
			continue;

		const uint64 hits = entry.hits.load(std::memory_order_relaxed);
		if (hits == 0)
			continue;

		entries.push_back({ AddressFromKey(key), hits });
	}

	std::ranges::sort(entries, [](const SnapshotEntry& lhs, const SnapshotEntry& rhs) {
		if (lhs.hits != rhs.hits)
			return lhs.hits > rhs.hits;
		return lhs.address < rhs.address;
	});

	const auto dumpDir = ActiveSettings::GetUserDataPath("dump/recompiler");
	std::error_code ec;
	fs::create_directories(dumpDir, ec);

	const auto dumpPath = dumpDir / fmt::format("hot_blocks_{}.txt", BuildTimestamp());
	std::ofstream file(dumpPath);
	if (!file)
	{
		cemuLog_log(LogType::Force, "PPC hot-block profiler: failed to write {}", _pathToUtf8(dumpPath));
		return {};
	}

	fmt::println(file, "# Cemu for AYN Thor Experiment guest PPC hot blocks");
	fmt::println(file, "# Columns: ppc_address estimated_hit_count module module_offset opcode0..opcode3 detail");
	fmt::println(file, "# sample_rate {}", kHotBlockSampleRate);
	fmt::println(file, "# estimated_total_samples {}", s_totalSamples.load(std::memory_order_relaxed));
	fmt::println(file, "# estimated_overflow_samples {}", s_overflowSamples.load(std::memory_order_relaxed));
	fmt::println(file, "# unique_blocks {}", entries.size());
	WriteModuleMap(file);
	fmt::println(file, "");
	for (const auto& entry : entries)
	{
		const auto [module, moduleOffset] = GetModuleAndOffset(entry.address);
		fmt::println(file, "0x{:08x}\t{}\t{}\t+0x{:x}\t{}\t{}",
			entry.address,
			entry.hits,
			module,
			moduleOffset,
			FormatOpcodeWindow(entry.address),
			FormatBlockDetails(entry.address));
	}

	const auto pathString = _pathToUtf8(dumpPath);
	cemuLog_log(LogType::Force, "PPC hot-block profiler: wrote {} blocks to {}", entries.size(), pathString);
	for (size_t i = 0; i < std::min<size_t>(entries.size(), 16); ++i)
	{
		const auto [module, moduleOffset] = GetModuleAndOffset(entries[i].address);
		cemuLog_log(LogType::Force, "PPC hot block #{:02}: 0x{:08x} {}+0x{:x} est hits {}",
			i + 1,
			entries[i].address,
			module,
			moduleOffset,
			entries[i].hits);
	}

	return pathString;
}

void PPCRecompilerProfiler::RecordHotBlock(uint32 ppcAddress)
{
	const uint32 key = MakeAddressKey(ppcAddress);
	const size_t startIndex = HashAddress(ppcAddress) & (kHotBlockTableSize - 1);
	s_totalSamples.fetch_add(kHotBlockSampleRate, std::memory_order_relaxed);

	for (size_t probe = 0; probe < kHotBlockProbeCount; ++probe)
	{
		HotBlockEntry& entry = s_hotBlocks[(startIndex + probe) & (kHotBlockTableSize - 1)];
		uint32 observedKey = entry.addressKey.load(std::memory_order_relaxed);
		if (observedKey == key)
		{
			entry.hits.fetch_add(kHotBlockSampleRate, std::memory_order_relaxed);
			return;
		}

		if (observedKey == 0 &&
			entry.addressKey.compare_exchange_strong(observedKey, key, std::memory_order_relaxed, std::memory_order_relaxed))
		{
			entry.hits.fetch_add(kHotBlockSampleRate, std::memory_order_relaxed);
			return;
		}

		if (observedKey == key)
		{
			entry.hits.fetch_add(kHotBlockSampleRate, std::memory_order_relaxed);
			return;
		}
	}

	s_overflowSamples.fetch_add(kHotBlockSampleRate, std::memory_order_relaxed);
}
