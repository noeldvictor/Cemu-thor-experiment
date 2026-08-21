#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/HW/Latte/Core/LatteDraw.h"
#include "Cafe/HW/Latte/Core/LatteTexture.h"
#include "Cafe/HW/Latte/Renderer/Renderer.h"
#include "Common/cpu_features.h"

#include <cstring>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

std::unordered_set<LatteTexture*> g_allTextures;

namespace
{
	bool LatteTC_IsRegisteredTextureLocked(LatteTexture* tex)
	{
		return tex && g_allTextures.find(tex) != g_allTextures.end();
	}
}

void LatteTC_Init()
{
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	cemu_assert_debug(g_allTextures.empty());
}

void LatteTC_RegisterTexture(LatteTexture* tex)
{
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	g_allTextures.emplace(tex);
}

void LatteTC_UnregisterTexture(LatteTexture* tex)
{
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	g_allTextures.erase(tex);
}

bool LatteTC_IsRegisteredTexture(LatteTexture* tex)
{
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	return LatteTC_IsRegisteredTextureLocked(tex);
}

template<typename T>
T LatteTC_ReadUnaligned(const void* ptr)
{
	T value;
	std::memcpy(&value, ptr, sizeof(T));
	return value;
}


// Samples one uint32 every strideBytes, sampleCount times, folding each with an add and a
// rotate.
//
// The straightforward form carries hashVal through that add+rotate on every iteration, so
// the scan serialises on a ~2 cycle dependency chain no matter how fast the loads retire.
// Four independent accumulators give the core four chains to interleave.
//
// Profiling Star Fox Zero on the Thor traces LatteCP_processCommandBuffer ->
// draw_beginSequence -> LatteTexture_updateTexturesForStage -> LatteTC_HasTextureChanged ->
// here; this is where essentially all of HasTextureChanged's time goes, and it measured 0.88%
// of total emulator CPU in self time - the largest single non-kernel symbol on the GPU thread
// after the command processor read itself. These loops were scalar on every platform, not
// just ARM: only the huge-texture branch ever had a SIMD path.
//
// Changing how the samples are folded changes the hash value, which is fine: texDataHash2 is
// only ever compared against another hash computed for the same texture in the same process
// and is never serialised. The AVX2, NEON and scalar paths already disagree with each other.
static uint32 LatteTC_StridedRotateHash(const uint8* data, uint32 sampleCount, uint32 strideBytes)
{
	uint32 h0 = 0, h1 = 0, h2 = 0, h3 = 0;
	const uint32 stride2 = strideBytes * 2;
	const uint32 stride3 = strideBytes * 3;
	const uint32 stride4 = strideBytes * 4;
	uint32 quadCount = sampleCount / 4;
	while (quadCount--)
	{
		h0 += LatteTC_ReadUnaligned<uint32>(data);
		h1 += LatteTC_ReadUnaligned<uint32>(data + strideBytes);
		h2 += LatteTC_ReadUnaligned<uint32>(data + stride2);
		h3 += LatteTC_ReadUnaligned<uint32>(data + stride3);
		h0 = (h0 << 3) | (h0 >> 29);
		h1 = (h1 << 3) | (h1 >> 29);
		h2 = (h2 << 3) | (h2 >> 29);
		h3 = (h3 << 3) | (h3 >> 29);
		data += stride4;
	}
	uint32 remaining = sampleCount & 3;
	while (remaining--)
	{
		h0 += LatteTC_ReadUnaligned<uint32>(data);
		h0 = (h0 << 3) | (h0 >> 29);
		data += strideBytes;
	}
	return h0 + h1 + h2 + h3;
}

// sample few uint64s uniformly over memory range
uint32 _quickStochasticHash(void* texData, uint32 memRange)
{
	auto* texDataU8 = static_cast<uint8*>(texData);

	uint64 hashVal = 0;
	memRange /= sizeof(uint64);

	uint32 memStep = memRange / 37; // use prime here to avoid memStep aligning nicely with pitch of texture, leading to sampling only along the border of a texture
	for (sint32 i = 0; i < 37; i++)
	{
		hashVal += LatteTC_ReadUnaligned<uint64>(texDataU8);
		hashVal = (hashVal << 3) | (hashVal >> 61);
		texDataU8 += memStep * sizeof(uint64);
	}
	return (uint32)hashVal ^ (uint32)(hashVal >> 32);
}

uint32 LatteTexture_CalculateTextureDataHash(LatteTexture* hostTexture)
{
	if( hostTexture->texDataPtrHigh == hostTexture->texDataPtrLow )
	{
		return 0;
	}
	uint32 memRange = hostTexture->texDataPtrHigh - hostTexture->texDataPtrLow;
	if (!memory_isAddressRangeAccessible(memory_physicalToVirtual(hostTexture->texDataPtrLow), memRange))
		return hostTexture->texDataHash2;

	if (hostTexture->format == Latte::E_GX2SURFFMT::R11_G11_B10_FLOAT)
	{
		// this is an exotic format that usually isn't generated or updated CPU-side
		// therefore as an optimization we can risk to only check a minimal amount of bytes at the beginning of the texture data
		// updates which change the entire texture should still be detected this way
		// this also helps with a bug in BotW which seems to fill the empty areas of the textures with other data which causes unnecessary invalidations and texture reloads

		// Wonderful 101 generates this format in a 8x8x8 3D texture using tiling aperture
		if (hostTexture->tileMode == Latte::E_HWTILEMODE::TM_1D_TILED_THICK && hostTexture->depth == 8 && hostTexture->width == 8 && hostTexture->height == 8)
		{
			// special case for Wonderful 101
			uint8* texDataU8 = memory_getPointerFromPhysicalOffset(hostTexture->texDataPtrLow);
			return LatteTC_ReadUnaligned<uint32>(texDataU8) ^
				LatteTC_ReadUnaligned<uint32>(texDataU8 + 0x100) ^
				LatteTC_ReadUnaligned<uint32>(texDataU8 + 0x200) ^
				LatteTC_ReadUnaligned<uint32>(texDataU8 + 0x300); // check the first thick slice (each slice has 0x400 bytes, with 0x100 bytes between layers)
		}
		uint8* texDataU8 = memory_getPointerFromPhysicalOffset(hostTexture->texDataPtrLow);
		return LatteTC_ReadUnaligned<uint32>(texDataU8) ^
			LatteTC_ReadUnaligned<uint32>(texDataU8 + 4) ^
			LatteTC_ReadUnaligned<uint32>(texDataU8 + 8) ^
			LatteTC_ReadUnaligned<uint32>(texDataU8 + 12);
	}

	uint8* texDataU8 = memory_getPointerFromPhysicalOffset(hostTexture->texDataPtrLow);
	uint32 hashVal = 0;
	uint32 pixelCount = hostTexture->width*hostTexture->height;

	bool isCompressedFormat = hostTexture->IsCompressedFormat();
	if (isCompressedFormat || hostTexture->useLightHash)
	{
		// check only 32 samples of the texture
		if (memRange < 256)
		{
			hashVal = LatteTC_StridedRotateHash(texDataU8, memRange / sizeof(uint32), sizeof(uint32));
		}
		else
		{
			hashVal = _quickStochasticHash(texDataU8, memRange);
		}
		return hashVal;
	}


	if( pixelCount <= (700*700) )
	{
		// small texture size
		bool isCompressedFormat = hostTexture->IsCompressedFormat();
		if( isCompressedFormat == false || memRange < 0x200 )
		{
			hashVal = LatteTC_StridedRotateHash(texDataU8, memRange / (4 * sizeof(uint32)), 4 * sizeof(uint32));
		}
		else
		{
			hashVal = LatteTC_StridedRotateHash(texDataU8, memRange / (32 * sizeof(uint32)), 32 * sizeof(uint32));
		}
	}
	else if( pixelCount <= (1200*1200) )
	{
		// medium texture size
		bool isCompressedFormat = hostTexture->IsCompressedFormat();
		if( isCompressedFormat == false )
		{
			hashVal = LatteTC_StridedRotateHash(texDataU8, memRange / (12 * sizeof(uint32)), 12 * sizeof(uint32));
		}
		else
		{
			hashVal = LatteTC_StridedRotateHash(texDataU8, memRange / (96 * sizeof(uint32)), 96 * sizeof(uint32));
		}
	}
	else
	{
		// huge texture size
		bool isCompressedFormat = hostTexture->IsCompressedFormat();
		if( isCompressedFormat == false )
		{
#if BOOST_OS_WINDOWS
			if (g_CPUFeatures.x86.avx2)
			{
				__m256i h256 = { 0 };
				__m256i* readPtr = (__m256i*)texDataU8;
				memRange /= (288);
				while (memRange--)
				{
					__m256i temp = _mm256_loadu_si256(readPtr);
					readPtr += (288 / 32);
					h256 = _mm256_xor_si256(h256, temp);
				}
#ifdef __clang__
				hashVal = h256[0] + h256[1] + h256[2] + h256[3] + h256[4] + h256[5] + h256[6] + h256[7];
#else
				hashVal = h256.m256i_u32[0] + h256.m256i_u32[1] + h256.m256i_u32[2] + h256.m256i_u32[3] + h256.m256i_u32[4] + h256.m256i_u32[5] + h256.m256i_u32[6] + h256.m256i_u32[7];
#endif
			}
#elif defined(__aarch64__)
			if (true)
			{
				// NEON counterpart of the AVX2 path above. Without this the Thor runs the
				// scalar loop below, whose rotate is loop-carried and therefore serializes
				// the whole scan - and this runs for every large texture, every frame.
				//
				// It is safe for this to produce different hash values than the x86 or the
				// scalar path: texDataHash2 is only ever compared against another hash
				// computed for the same texture in the same process, never serialized. The
				// AVX2 and scalar paths already disagree with each other for the same reason.
				uint32x4_t h0 = vdupq_n_u32(0);
				uint32x4_t h1 = vdupq_n_u32(0);
				memRange /= 288;
				while (memRange--)
				{
					// vld1q_u8 on a byte pointer - guest texture addresses are not
					// guaranteed to be host aligned, so never cast to a wider pointer here
					h0 = veorq_u32(h0, vreinterpretq_u32_u8(vld1q_u8(texDataU8)));
					h1 = veorq_u32(h1, vreinterpretq_u32_u8(vld1q_u8(texDataU8 + 16)));
					texDataU8 += 288;
				}
				hashVal = vaddvq_u32(veorq_u32(h0, h1));
			}
#else
			if( false ) {}
#endif
			else
			{
				memRange /= (32 * sizeof(uint64));
				uint64 h64 = 0;
				while (memRange--)
				{
					h64 += LatteTC_ReadUnaligned<uint64>(texDataU8);
					h64 = (h64 << 3) | (h64 >> 61);
					texDataU8 += 32 * sizeof(uint64);
				}
				hashVal = (h64 & 0xFFFFFFFF) + (h64 >> 32);
			}
		}
		else
		{
			hashVal = LatteTC_StridedRotateHash(texDataU8, memRange / (512 * sizeof(uint32)), 512 * sizeof(uint32));
		}
	}

	return hashVal;
}

uint64 _botwLargeTexHax = 0;

bool LatteTC_HasTextureChanged(LatteTexture* hostTexture, bool force)
{
#if defined(__ANDROID__)
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	if (!LatteTC_IsRegisteredTextureLocked(hostTexture))
		return false;
#else
	if (!LatteTC_IsRegisteredTexture(hostTexture))
		return false;
#endif

	// Keep the normal texture invalidation path enabled on Android. The hash
	// scan must stay alignment-safe because guest texture addresses are not
	// guaranteed to satisfy ARM host load alignment.
	if (hostTexture->forceInvalidate)
	{
		force = true;
		debug_printf("Force invalidate 0x%08x\n", hostTexture->physAddress);
		hostTexture->forceInvalidate = false;
	}
	// if texture is written by GPU operations we switch to a faster hash implementation
	if (hostTexture->isUpdatedOnGPU && hostTexture->useLightHash == false)
	{
		hostTexture->useLightHash = true;
		// update hash
		hostTexture->texDataHash2 = LatteTexture_CalculateTextureDataHash(hostTexture);
	}
	// only check each texture for updates once a frame
	// todo: Instead of relying on frames, it would be better to recheck only after any GPU wait operation occurred.
	if( hostTexture->lastDataUpdateFrameCounter == LatteGPUState.frameCounter && force == false)
		return false;
	hostTexture->lastDataUpdateFrameCounter = LatteGPUState.frameCounter;
	// we assume that certain texture properties indicate that the texture will never be written by the CPU
	if (hostTexture->width == 1280 && hostTexture->format != Latte::E_GX2SURFFMT::R8_UNORM && force == false)
	{
		// todo - remove this or find a better way to handle excluded texture invalidation checks (maybe via game profile?)
		return false;
	}
	// workaround for corrupted terrain texture in BotW after video playback
	// probably would be fixed if we added support for invalidating individual slices/mips of a texture
	uint32 texDataHash = LatteTexture_CalculateTextureDataHash(hostTexture);
	if( texDataHash != hostTexture->texDataHash2 )
	{
		hostTexture->texDataHash2 = texDataHash;
		if (hostTexture->depth == 83 && hostTexture->width == 1024 && hostTexture->height == 1024)
		{
			_botwLargeTexHax = LatteGPUState.frameCounter;
		}
		return true;
	}
	if (_botwLargeTexHax != 0 && hostTexture->depth == 83 && hostTexture->width == 1024 && hostTexture->height == 1024 && _botwLargeTexHax != LatteGPUState.frameCounter)
	{
		_botwLargeTexHax = 0;
		return true;
	}
	return false;
}

void LatteTC_ResetTextureChangeTracker(LatteTexture* hostTexture, bool force)
{
#if defined(__ANDROID__)
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	if (!LatteTC_IsRegisteredTextureLocked(hostTexture))
		return;
#else
	if (!LatteTC_IsRegisteredTexture(hostTexture))
		return;
#endif

	if( hostTexture->lastDataUpdateFrameCounter == LatteGPUState.frameCounter && force == false)
		return;
	hostTexture->lastDataUpdateFrameCounter = LatteGPUState.frameCounter;
	LatteTC_HasTextureChanged(hostTexture, true);
}

/*
 * This function should be called whenever the texture is still used in some form (any kind of access counts)
 * The purpose of this function is to prevent garbage collection of textures that are still actively used
 */
void LatteTC_MarkTextureStillInUse(LatteTexture* texture)
{
#if defined(__ANDROID__)
	// Android currently disables texture GC and low-memory texture eviction, so
	// these timestamps are not consumed there. Avoid touching texture pointers
	// that can be stale during aggressive Vulkan view/texture aliasing.
	(void)texture;
	return;
#else
	if (!LatteTC_IsRegisteredTexture(texture))
		return;

	texture->lastAccessTick = LatteGPUState.currentDrawCallTick;
	texture->lastAccessFrameCount = LatteGPUState.frameCounter;
#endif
}

// check if a texture has been overwritten by another texture using GPU-writes
bool LatteTC_IsTextureDataOverwritten(LatteTexture* texture)
{
#if defined(__ANDROID__)
	(void)texture;
	return false;
#else
	if (!LatteTC_IsRegisteredTexture(texture) || !texture->sliceMipInfo)
		return false;

	// check overlaps
	sint32 mipLevels = texture->mipLevels;
	sint32 sliceCount = texture->depth;
	mipLevels = std::min(mipLevels, 3); // only check first 3 mip levels
	for (sint32 mipIndex = 0; mipIndex < mipLevels; mipIndex++)
	{
		sint32 mipSliceCount;
		if (texture->Is3DTexture())
			mipSliceCount = std::max(1, sliceCount >> mipIndex);
		else
			mipSliceCount = sliceCount;
		for (sint32 sliceIndex = 0; sliceIndex < mipSliceCount; sliceIndex++)
		{
			LatteTextureSliceMipInfo* sliceMipInfo = texture->sliceMipInfo + texture->GetSliceMipArrayIndex(sliceIndex, mipIndex);
			if (!LatteTexture_IsOwnedSliceMipInfo(texture, sliceMipInfo))
				return false;
			bool isSliceMipOutdated = false;
			for (auto overlapItr = sliceMipInfo->list_dataOverlap.begin(); overlapItr != sliceMipInfo->list_dataOverlap.end();)
			{
				auto& overlapData = *overlapItr;
				if (!LatteTC_IsRegisteredTexture(overlapData.destTexture) ||
					!LatteTexture_IsOwnedSliceMipInfo(overlapData.destTexture, overlapData.destMipSliceInfo))
				{
					overlapItr = sliceMipInfo->list_dataOverlap.erase(overlapItr);
					continue;
				}
				if (sliceMipInfo->lastDynamicUpdate < overlapData.destMipSliceInfo->lastDynamicUpdate)
				{
					isSliceMipOutdated = true;
					break;
				}
				++overlapItr;
			}
			if (isSliceMipOutdated == false)
				return false;
		}
	}
	return true;
#endif
}

void LatteTexture_Delete(LatteTexture* texture)
{
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	LatteTC_UnregisterTexture(texture);
	LatteMRT::NotifyTextureDeletion(texture);
	LatteTextureReadback_NotifyTextureDeletion(texture);
	LatteTexture_DeleteTextureRelations(texture);
	// delete views
	while (!texture->views.empty())
		delete texture->views[0];
	cemu_assert_debug(texture->views.empty());
	cemu_assert_debug(texture->baseView == nullptr);
	// free data overlap tracking
	LatteTexture_DeleteDataOverlapTracking(texture);
	// remove from lists
	LatteTexture_UnregisterTextureMemoryOccupancy(texture);
	// free memory
	if (texture->sliceMipInfo)
	{
		delete[] texture->sliceMipInfo;
		texture->sliceMipInfo = nullptr;
	}
	delete texture;
}

/*
 * Checks if the texture can be dropped from the cache and if yes, delete it
 * Returns true if the texture was deleted
 */
bool LatteTC_CleanupCheckTexture(LatteTexture* texture, uint32 currentTick)
{
	if (!LatteTC_IsRegisteredTexture(texture))
		return false;

	uint32 currentFrameCount = LatteGPUState.frameCounter;
	uint32 ticksSinceLastAccess = currentTick - texture->lastAccessTick;
	uint32 framesSinceLastAccess = currentFrameCount - texture->lastAccessFrameCount;
	if( !texture->isUpdatedOnGPU )
	{
		// RAM-only textures are safe to be deleted since we can always restore them from RAM
		if( ticksSinceLastAccess >= (120*1000) && framesSinceLastAccess >= 2000 )
		{
			LatteTexture_Delete(texture);
			return true;
		}
	}

	if ((LatteGPUState.currentDrawCallTick - texture->lastAccessTick) >= 100 && 
		LatteTC_IsTextureDataOverwritten(texture))
	{
		LatteTexture_Delete(texture);
		return true;
	}
	// if unused for more than 5 seconds, start deleting views since they are cheap to recreate
#if !defined(__ANDROID__)
	if (ticksSinceLastAccess >= 5 * 1000 && framesSinceLastAccess >= 30)
	{
		for (sint32 i = 0; i < 3; i++)
		{
			if (texture->views.size() <= 1)
				break;
			LatteTextureView* view = texture->views[0];
			if (view == texture->baseView)
				view = texture->views[1];
			delete view;
		}
	}
#endif
	return false;
}

void LatteTexture_RefreshInfoCache();

/*
 * Scans for unused textures and deletes them
 * Called at the end of every frame
 */
void LatteTC_CleanupUnusedTextures()
{
#if defined(__ANDROID__)
	return;
#else
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	static size_t currentScanIndex = 0;
	uint32 currentTick = GetTickCount();
	sint32 maxDelete = 10;
	std::vector<LatteTexture*> allTextures = LatteTexture_GetAllTexturesSnapshot();
	if (!allTextures.empty())
	{
		for (sint32 c = 0; c < 25; c++)
		{
			if (currentScanIndex >= allTextures.size())
				currentScanIndex = 0;
			LatteTexture* texItr = allTextures[currentScanIndex];
			currentScanIndex++;
			if (!LatteTC_IsRegisteredTexture(texItr))
				continue;
			if (LatteTC_CleanupCheckTexture(texItr, currentTick))
			{
				maxDelete--;
				if (maxDelete <= 0)
					break; // deleting can be an expensive operation, dont delete too many at once to avoid micro stutter
			}
		}
	}
	LatteTexture_RefreshInfoCache(); // find a better place to call this from?
#endif
}

std::vector<LatteTexture*> LatteTC_GetDeleteableTextures()
{
#if defined(__ANDROID__)
	return {};
#else
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	std::vector<LatteTexture*> texList;
	uint32 currentFrameCount = LatteGPUState.frameCounter;

	for (auto& itr : g_allTextures)
	{
		if(itr->lastAccessFrameCount == 0)
			continue; // not initialized
		uint32 framesSinceLastAccess = currentFrameCount - itr->lastAccessFrameCount;
		if(framesSinceLastAccess < 3)
			continue;
		if (itr->isUpdatedOnGPU)
		{
			if (LatteTC_IsTextureDataOverwritten(itr))
				texList.emplace_back(itr);
		}
		else
		{
			texList.emplace_back(itr);
		}
	}

	return texList;
#endif
}

void LatteTC_UnloadAllTextures()
{
	std::lock_guard lock(LatteTexture_GetRegistryMutex());
	std::vector<LatteTexture*> allTexturesCopy = LatteTexture_GetAllTexturesSnapshot();
	for (auto& itr : allTexturesCopy)
	{
		if(itr)
			LatteTexture_Delete(itr);
	}
	LatteRenderTarget_unloadAll();
}
