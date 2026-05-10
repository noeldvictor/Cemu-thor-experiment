#pragma once

namespace PPCRecompilerProfiler
{
	extern uint32 g_hotBlockProfilerEnabled;
	extern uint32 g_hotBlockProfilerSampleCounter;

	void SetHotBlockProfilerEnabled(bool enabled);
	bool IsHotBlockProfilerEnabled();
	void ResetHotBlockProfiler();
	std::string DumpHotBlockProfiler();
	void RecordHotBlock(uint32 ppcAddress);
}
