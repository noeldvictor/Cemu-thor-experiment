#include "cpu_features.h"

#if BOOST_OS_MACOS
#include <sys/types.h>
#include <sys/sysctl.h>
#endif

// wrappers with uniform prototype for implementation-specific x86 CPU id
#if defined(ARCH_X86_64)
#ifdef __GNUC__
#include <cpuid.h>
#endif

inline void cpuid(int cpuInfo[4], int functionId) {
#if defined(_MSC_VER)
	__cpuid(cpuInfo, functionId);
#elif defined(__GNUC__)
	__cpuid(functionId, cpuInfo[0], cpuInfo[1], cpuInfo[2], cpuInfo[3]);
#else
#error No definition for cpuid
#endif
}

inline void cpuidex(int cpuInfo[4], int functionId, int subFunctionId) {
#if defined(_MSC_VER)
	__cpuidex(cpuInfo, functionId, subFunctionId);
#elif defined(__GNUC__)
	__cpuid_count(functionId, subFunctionId, cpuInfo[0], cpuInfo[1], cpuInfo[2], cpuInfo[3]);
#else
#error No definition for cpuidex
#endif
}
#endif

#if defined(__aarch64__)
#if BOOST_OS_LINUX
std::string getCpuBrandNameLinux()
{
	static auto default_name = "unknown";
	std::ifstream ifstream("/proc/device-tree/model");
	if (!ifstream.is_open())
		return default_name;
	std::stringstream stringstream;
	stringstream << ifstream.rdbuf();
	std::string model = stringstream.str();
	if (model.empty())
		return default_name;
	return model;
}
#if BOOST_PLAT_ANDROID

#include <sys/system_properties.h>

std::string getProperty(const std::string& name)
{
	const prop_info* pi = __system_property_find(name.c_str());
	std::string propValue;
	if (pi == nullptr)
		return {};
	__system_property_read_callback(
		pi,
		[](void* cookie, const char* name, const char* value, uint32_t serial) {
			if (cookie == nullptr)
				return;
			*reinterpret_cast<std::string*>(cookie) = value;
		},
		&propValue);
	return propValue;
}

std::string getCpuBrandNameAndroid()
{
	static auto propertiesNames = {
		"ro.soc.manufacturer",
		"ro.soc.model",
		"ro.boot.hardware.revision",
	};
    std::string tmp;
	for (auto&& propertyName : propertiesNames)
	{
		auto propertyValue = getProperty(propertyName);
		if (!propertyValue.empty())
        {
            if (!tmp.empty())
                tmp.append(", ");
            tmp.append(propertyValue);
        }
	}
	if (tmp.empty())
		return getCpuBrandNameLinux();
    return tmp;
}
#endif // BOOST_PLAT_ANDROID

#include <sys/auxv.h>
#if __has_include(<asm/hwcap.h>)
#include <asm/hwcap.h>
#endif

// Fallbacks in case the NDK/libc headers in use predate a given bit. The values
// are fixed by the kernel ABI (Documentation/arm64/elf_hwcaps.rst).
#ifndef HWCAP_ATOMICS
#define HWCAP_ATOMICS (1u << 8)
#endif
#ifndef HWCAP_ASIMDHP
#define HWCAP_ASIMDHP (1u << 10)
#endif
#ifndef HWCAP_SHA3
#define HWCAP_SHA3 (1u << 17)
#endif
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1u << 20)
#endif
#ifndef HWCAP_SVE
#define HWCAP_SVE (1u << 22)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1u << 13)
#endif
#endif // BOOST_OS_LINUX

#if BOOST_OS_MACOS
// Apple exposes feature bits through sysctlbyname rather than auxv.
static bool sysctlFeatureEnabled(const char* name)
{
	int value = 0;
	size_t size = sizeof(value);
	if (sysctlbyname(name, &value, &size, nullptr, 0) != 0)
		return false;
	return value != 0;
}
#endif
#endif // defined(__aarch64__)

CPUFeaturesImpl::CPUFeaturesImpl()
{
#if defined(__aarch64__)
#if BOOST_PLAT_ANDROID
	m_cpuBrandName = getCpuBrandNameAndroid();
#elif BOOST_OS_LINUX
	m_cpuBrandName = getCpuBrandNameLinux();
#endif

#if BOOST_OS_LINUX
	{
		const unsigned long hwcap = getauxval(AT_HWCAP);
		const unsigned long hwcap2 = getauxval(AT_HWCAP2);
		arm.atomics = (hwcap & HWCAP_ATOMICS) != 0;
		arm.asimdhp = (hwcap & HWCAP_ASIMDHP) != 0;
		arm.asimddp = (hwcap & HWCAP_ASIMDDP) != 0;
		arm.sha3 = (hwcap & HWCAP_SHA3) != 0;
		arm.sve = (hwcap & HWCAP_SVE) != 0;
		arm.i8mm = (hwcap2 & HWCAP2_I8MM) != 0;
	}
#elif BOOST_OS_MACOS
	arm.atomics = true; // mandatory from ARMv8.1, all Apple Silicon has it
	arm.asimdhp = sysctlFeatureEnabled("hw.optional.arm.FEAT_FP16");
	arm.asimddp = sysctlFeatureEnabled("hw.optional.arm.FEAT_DotProd");
	arm.i8mm = sysctlFeatureEnabled("hw.optional.arm.FEAT_I8MM");
	arm.sha3 = sysctlFeatureEnabled("hw.optional.armv8_2_sha3");
	arm.sve = sysctlFeatureEnabled("hw.optional.arm.FEAT_SVE");
#endif
#endif

#if BOOST_OS_MACOS
	std::string cpuName;
	size_t size = 0;

	if (sysctlbyname("machdep.cpu.brand_string", nullptr, &size, nullptr, 0) == 0 && size > 0)
	{
		std::vector<char> buffer(size);

		if (sysctlbyname("machdep.cpu.brand_string", buffer.data(), &size, nullptr, 0) == 0 && size > 0)
		{
			cpuName.assign(buffer.data());
		}
	}

	strncpy(m_cpuBrandName, cpuName.c_str(), sizeof(m_cpuBrandName) - 1);
	m_cpuBrandName[sizeof(m_cpuBrandName) - 1] = '\0';
#elif defined(ARCH_X86_64)
	int cpuInfo[4];
	cpuid(cpuInfo, 0x80000001);
	x86.lzcnt = ((cpuInfo[2] >> 5) & 1) != 0;
	cpuid(cpuInfo, 0x1);
	x86.movbe = ((cpuInfo[2] >> 22) & 1) != 0;
	x86.avx = ((cpuInfo[2] >> 28) & 1) != 0;
	x86.aesni = ((cpuInfo[2] >> 25) & 1) != 0;
	x86.ssse3 = ((cpuInfo[2] >> 9) & 1) != 0;
	x86.sse4_1 = ((cpuInfo[2] >> 19) & 1) != 0;
	cpuidex(cpuInfo, 0x7, 0);
	x86.avx2 = ((cpuInfo[1] >> 5) & 1) != 0;
	x86.bmi2 = ((cpuInfo[1] >> 8) & 1) != 0;
	cpuid(cpuInfo, 0x80000007);
	x86.invariant_tsc = ((cpuInfo[3] >> 8) & 1);
	// get CPU brand name
	uint32_t nExIds, i = 0;
	char cpuBrandName[0x40]{ 0 };
	memset(cpuBrandName, 0, sizeof(cpuBrandName));
	cpuid(cpuInfo, 0x80000000);
	nExIds = (uint32_t)cpuInfo[0];
	for (uint32_t i = 0x80000000; i <= nExIds; ++i)
	{
		cpuid(cpuInfo, i);
		if (i == 0x80000002)
			memcpy(cpuBrandName, cpuInfo, sizeof(cpuInfo));
		else if (i == 0x80000003)
			memcpy(cpuBrandName + 16, cpuInfo, sizeof(cpuInfo));
		else if (i == 0x80000004)
			memcpy(cpuBrandName + 32, cpuInfo, sizeof(cpuInfo));
	}
	m_cpuBrandName = cpuBrandName;
#endif
}

std::string CPUFeaturesImpl::GetCPUName()
{
	return { m_cpuBrandName };
}

std::string CPUFeaturesImpl::GetCommaSeparatedExtensionList()
{
	std::string tmp;
	auto appendExt = [&tmp](const char* str)
	{
		if (!tmp.empty())
			tmp.append(", ");
		tmp.append(str);
	};
	if (x86.ssse3)
		appendExt("SSSE3");
	if (x86.sse4_1)
		appendExt("SSE4.1");
	if (x86.avx)
		appendExt("AVX");
	if (x86.avx2)
		appendExt("AVX2");
	if (x86.lzcnt)
		appendExt("LZCNT");
	if (x86.movbe)
		appendExt("MOVBE");
	if (x86.bmi2)
		appendExt("BMI2");
	if (x86.aesni)
		appendExt("AES-NI");
	if(x86.invariant_tsc)
		appendExt("INVARIANT-TSC");
	if (arm.atomics)
		appendExt("LSE");
	if (arm.asimdhp)
		appendExt("FP16");
	if (arm.asimddp)
		appendExt("DOTPROD");
	if (arm.i8mm)
		appendExt("I8MM");
	if (arm.sha3)
		appendExt("SHA3");
	if (arm.sve)
		appendExt("SVE");
	return tmp;
}

CPUFeaturesImpl g_CPUFeatures;