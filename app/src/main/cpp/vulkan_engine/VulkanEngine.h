#pragma once

#include "VulkanDevice.h"
#include "VulkanImage.h"
#include "VulkanBuffer.h"
#include "VulkanPipeline.h"
#include "VulkanCommandPool.h"

#include <vulkan/vulkan.h>
#include <vector>
#include <cstdint>
#include <mutex>
#include <map>
#include <string>

// Must match the shader's uniform block layout exactly.
// All members are 4-byte scalars (float / int) and there is no array, so std140
// lays them out consecutively without padding.
// Total: 26 base (21 float + 5 int) + 24 LCH + 2 enable + 7 extended grain
//        = 59 fields * 4 bytes = 236 bytes.
struct FilterParams {
    float lutStrength;         // 0
    float lut2Strength;        // 1
    float lutSize;             // 2
    float lut2Size;            // 3
    float exposure;            // 4
    float contrast;            // 5
    float highlights;          // 6
    float shadows;             // 7
    float saturation;          // 8
    float colorTemperature;    // 9
    float tint;                // 10
    float vignetteStrength;    // 11
    float grainStrength;       // 12
    float grainSeed;           // 13
    float shadowThreshold;     // 14  (default 0.33)
    float highlightThreshold;  // 15  (default 0.66)
    float shadowGrainRatio;    // 16
    float midtoneGrainRatio;   // 17
    float highlightGrainRatio; // 18
    float shadowSizeRatio;     // 19
    float highlightSizeRatio;  // 20
    int   enableLut;           // 21
    int   enableLut2;          // 22
    int   enableGrain;         // 23
    int   width;               // 24
    int   height;              // 25
    // LCH adjustments: 8 zones x 3 (lightness, chroma, hue)
    float lchRed_L, lchRed_C, lchRed_H;
    float lchOrange_L, lchOrange_C, lchOrange_H;
    float lchYellow_L, lchYellow_C, lchYellow_H;
    float lchGreen_L, lchGreen_C, lchGreen_H;
    float lchCyan_L, lchCyan_C, lchCyan_H;
    float lchBlue_L, lchBlue_C, lchBlue_H;
    float lchPurple_L, lchPurple_C, lchPurple_H;
    float lchSkin_L, lchSkin_C, lchSkin_H;
    int   enableLch;
    int   enableCurve;
    // Extended grain parameters
    float midtoneSizeRatio;
    float redChannelRatio;
    float blueChannelRatio;
    float channelCorrelation;
    float colorPreservation;
    float grainSize;
    float lutGlobalStrength;
};

class VulkanEngine {
public:
    bool init(const std::map<std::string, std::vector<uint8_t>>& shaderData);
    void destroy();
    bool isAvailable() const;

    void uploadLut(const float* lutData, uint32_t lutSize, int slot);

    // Upload 1024 floats of curve data (R:0-255, G:256-511, B:512-767, W:768-1023)
    void uploadCurves(const float* curveData);

    // Process RGBA8 image in-place. rgbaData must be width*height*4 bytes.
    // The buffer is read, uploaded to GPU, processed, and result written back
    // to the same buffer.
    bool processImage(uint8_t* rgbaData, uint32_t width, uint32_t height,
                      const FilterParams& params);

private:
    std::mutex m_mutex;
    VulkanDevice m_device;
    VulkanCommandPool m_cmdPool;

    // Ping-pong storage images (RGBA8)
    VulkanImage m_storageImage[2];

    // LUT 3D textures (dual slot support)
    VulkanImage m_lutImage[2];

    // Upload/download buffer (host-visible, reusable)
    VulkanBuffer m_transferBuffer;

    // UBO for FilterParams
    VulkanBuffer m_uboBuffer;

    // SSBO for curve data (1024 floats = 4096 bytes)
    VulkanBuffer m_curveBuffer;

    // Single unified pipeline
    VulkanPipeline m_pipeline;

    // Descriptor resources
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;

    // Two descriptor sets for ping-pong:
    //   set A (m_descriptorSets[0]): read image[0], write image[1]
    //   set B (m_descriptorSets[1]): read image[1], write image[0]
    VkDescriptorSet m_descriptorSets[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};

    std::map<std::string, std::vector<uint8_t>> m_shaderData;
    uint32_t m_currentWidth = 0;
    uint32_t m_currentHeight = 0;
    bool m_initialized = false;

    // LUT upload state tracking
    bool m_lutUploaded[2] = {false, false};

    bool createDescriptorLayout();
    bool createDescriptorPool();
    bool allocateDescriptorSets();
    bool createPipeline();
    bool ensureImageSize(uint32_t width, uint32_t height);
    bool createUbo();
    bool uploadUbo(const FilterParams& params);
    void initStaticDescriptors();
    void updateDescriptorSets();
    void updateLutDescriptors();
};
