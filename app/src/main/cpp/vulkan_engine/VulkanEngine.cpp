#include "VulkanEngine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "VulkanEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Workgroup size must match the unified compute shader (16x16x1)
static constexpr uint32_t WORKGROUP_SIZE_X = 16;
static constexpr uint32_t WORKGROUP_SIZE_Y = 16;

// Convert single-precision float to half-precision (IEEE 754 binary16)
static uint16_t floatToHalf(float f) {
    uint32_t u;
    memcpy(&u, &f, sizeof(u));
    uint16_t sign = static_cast<uint16_t>((u >> 16) & 0x8000);
    int32_t exp = static_cast<int32_t>(((u >> 23) & 0xFF) - 127 + 15);
    uint32_t mantissa = u & 0x007FFFFF;
    if (exp >= 31) {
        return sign | 0x7C00;
    }
    if (exp <= 0) {
        if (exp <= -10) return sign;
        mantissa = (mantissa | 0x00800000) >> (1 - exp);
        return sign | static_cast<uint16_t>(mantissa >> 13);
    }
    return sign | static_cast<uint16_t>(exp << 10) | static_cast<uint16_t>(mantissa >> 13);
}

// ============================================================================
// init
// ============================================================================
bool VulkanEngine::init(const std::map<std::string, std::vector<uint8_t>>& shaderData) {
    m_shaderData = shaderData;

    if (!m_device.init()) {
        LOGE("Failed to initialize Vulkan device");
        return false;
    }
    if (!m_cmdPool.init(m_device)) {
        LOGE("Failed to initialize command pool");
        return false;
    }
    if (!createDescriptorLayout()) {
        LOGE("Failed to create descriptor set layout");
        return false;
    }
    if (!createDescriptorPool()) {
        LOGE("Failed to create descriptor pool");
        return false;
    }
    if (!allocateDescriptorSets()) {
        LOGE("Failed to allocate descriptor sets");
        return false;
    }
    if (!createPipeline()) {
        LOGE("Failed to create compute pipeline");
        return false;
    }
    if (!createUbo()) {
        LOGE("Failed to create UBO buffer");
        return false;
    }

    // Create curve data SSBO (1028 floats = 4112 bytes: 1024 LUT + 4 sentinels)
    if (!m_curveBuffer.create(m_device, 4112,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        LOGE("Failed to create curve buffer");
        return false;
    }

    // Initialize curve buffer with identity (default no-op curves)
    float identityCurves[1028];
    for (int i = 0; i < 256; i++) {
        float v = i / 255.0f;
        identityCurves[i] = v;          // R
        identityCurves[256 + i] = v;    // G
        identityCurves[512 + i] = v;    // B
        identityCurves[768 + i] = v;    // W (master)
    }
    // Sentinel flags at 1024-1027: 0 = no user points
    identityCurves[1024] = 0.0f;
    identityCurves[1025] = 0.0f;
    identityCurves[1026] = 0.0f;
    identityCurves[1027] = 0.0f;
    m_curveBuffer.upload(m_device.device(), identityCurves, 4112);

    // Wire UBO and Curve SSBO descriptors once — these buffers never change
    initStaticDescriptors();

    // Create placeholder 1x1x1 LUT images to avoid null descriptors
    for (int i = 0; i < 2; i++) {
        VkFormat fmt = VK_FORMAT_R32G32B32A32_SFLOAT;
        if (!m_lutImage[i].createSampledImage3D(m_device, 1, 1, 1, fmt)) {
            fmt = VK_FORMAT_R16G16B16A16_SFLOAT;
            if (!m_lutImage[i].createSampledImage3D(m_device, 1, 1, 1, fmt)) {
                fmt = VK_FORMAT_R8G8B8A8_UNORM;
                if (!m_lutImage[i].createSampledImage3D(m_device, 1, 1, 1, fmt)) {
                    LOGE("Failed to create placeholder LUT[%d] — null descriptor fallback", i);
                }
            }
        }
    }

    // Transition placeholder LUTs to SHADER_READ_ONLY so descriptor layout matches
    {
        VkCommandBuffer cmd = m_cmdPool.beginSingleTimeCommands(m_device);
        if (cmd != VK_NULL_HANDLE) {
            for (int i = 0; i < 2; i++) {
                if (m_lutImage[i].image() != VK_NULL_HANDLE) {
                    m_lutImage[i].transitionLayout(cmd, VK_IMAGE_LAYOUT_UNDEFINED,
                                                   VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
            m_cmdPool.endSingleTimeCommands(m_device, cmd);
        }
    }

    m_initialized = true;
    LOGI("VulkanEngine initialized successfully (unified RGBA8 pipeline)");
    return true;
}

// ============================================================================
// destroy
// ============================================================================
void VulkanEngine::destroy() {
    VkDevice dev = m_device.device();
    if (dev == VK_NULL_HANDLE) return;

    vkDeviceWaitIdle(dev);

    // Buffers
    m_uboBuffer.destroy(dev);
    m_curveBuffer.destroy(dev);
    m_transferBuffer.destroy(dev);

    // Images
    for (int i = 0; i < 2; i++) {
        m_storageImage[i].destroy(dev);
        m_lutImage[i].destroy(dev);
    }

    // Pipeline
    m_pipeline.destroy(dev);

    // Descriptor resources
    if (m_descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(dev, m_descriptorPool, nullptr);
        m_descriptorPool = VK_NULL_HANDLE;
    }
    if (m_descriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(dev, m_descriptorSetLayout, nullptr);
        m_descriptorSetLayout = VK_NULL_HANDLE;
    }

    m_cmdPool.destroy(dev);
    m_device.destroy();

    m_initialized = false;
    m_currentWidth = 0;
    m_currentHeight = 0;
    m_lutUploaded[0] = false;
    m_lutUploaded[1] = false;

    LOGI("VulkanEngine destroyed");
}

// ============================================================================
// isAvailable
// ============================================================================
bool VulkanEngine::isAvailable() const {
    return m_initialized;
}

// ============================================================================
// createDescriptorLayout
//
// Single layout with 6 bindings:
//   0: storage image (input, readonly)
//   1: storage image (output)
//   2: combined image sampler (LUT1 3D texture)
//   3: combined image sampler (LUT2 3D texture)
//   4: uniform buffer (FilterParams)
//   5: storage buffer (CurveData SSBO)
// ============================================================================
bool VulkanEngine::createDescriptorLayout() {
    VkDevice dev = m_device.device();

    VkDescriptorSetLayoutBinding bindings[6]{};

    // binding 0: storage image (input, readonly)
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // binding 1: storage image (output)
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // binding 2: combined image sampler (LUT1)
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // binding 3: combined image sampler (LUT2)
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // binding 4: uniform buffer (FilterParams)
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // binding 5: storage buffer (CurveData SSBO)
    bindings[5].binding = 5;
    bindings[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[5].descriptorCount = 1;
    bindings[5].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 6;
    layoutInfo.pBindings = bindings;

    if (vkCreateDescriptorSetLayout(dev, &layoutInfo, nullptr, &m_descriptorSetLayout) != VK_SUCCESS) {
        LOGE("Failed to create unified descriptor set layout");
        return false;
    }

    LOGI("Unified descriptor set layout created (6 bindings)");
    return true;
}

// ============================================================================
// createDescriptorPool
// ============================================================================
bool VulkanEngine::createDescriptorPool() {
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          4}, // 2 storage images x 2 descriptor sets
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,  4}, // 2 LUT samplers x 2 descriptor sets
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,          2}, // 1 UBO x 2 descriptor sets
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,          2}  // 1 curve SSBO x 2 descriptor sets
    };

    VkDescriptorPoolCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    info.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    info.maxSets = 2; // ping-pong: set A and set B
    info.poolSizeCount = 4;
    info.pPoolSizes = sizes;

    if (vkCreateDescriptorPool(m_device.device(), &info, nullptr, &m_descriptorPool) != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool");
        return false;
    }

    LOGI("Descriptor pool created");
    return true;
}

// ============================================================================
// allocateDescriptorSets
// ============================================================================
bool VulkanEngine::allocateDescriptorSets() {
    VkDevice dev = m_device.device();

    VkDescriptorSetLayout layouts[] = {m_descriptorSetLayout, m_descriptorSetLayout};

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = m_descriptorPool;
    allocInfo.descriptorSetCount = 2;
    allocInfo.pSetLayouts = layouts;

    if (vkAllocateDescriptorSets(dev, &allocInfo, m_descriptorSets) != VK_SUCCESS) {
        LOGE("Failed to allocate ping-pong descriptor sets");
        return false;
    }

    LOGI("Ping-pong descriptor sets allocated (set A, set B)");
    return true;
}

// ============================================================================
// createPipeline
// ============================================================================
bool VulkanEngine::createPipeline() {
    auto it = m_shaderData.find("unified.comp.spv");
    if (it == m_shaderData.end()) {
        LOGE("Shader not found: unified.comp.spv");
        return false;
    }
    LOGI("Loaded shader: unified.comp.spv (%zu bytes)", it->second.size());

    // No push constants -- all parameters come through the UBO
    if (!m_pipeline.createComputePipeline(m_device, it->second,
                                          m_descriptorSetLayout, 0)) {
        LOGE("Failed to create unified compute pipeline");
        return false;
    }

    LOGI("Unified compute pipeline created");
    return true;
}

// ============================================================================
// createUbo
// ============================================================================
bool VulkanEngine::createUbo() {
    // FilterParams is 59 fields * 4 = 236 bytes (see the layout comment in
    // VulkanEngine.h). The uniform *range* bound in the descriptor set is
    // sizeof(FilterParams); the allocation itself is rounded up to 256 to match
    // the usual minUniformBufferOffsetAlignment and leave head-room for params
    // added later.
    constexpr VkDeviceSize uboSize = 256;
    static_assert(sizeof(FilterParams) <= uboSize,
                  "FilterParams no longer fits in the UBO allocation");

    if (!m_uboBuffer.create(m_device, uboSize,
                            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        LOGE("Failed to create UBO buffer");
        return false;
    }

    LOGI("UBO buffer created (%zu bytes)", (size_t)uboSize);
    return true;
}

// ============================================================================
// uploadUbo -- upload FilterParams to the UBO buffer
// ============================================================================
bool VulkanEngine::uploadUbo(const FilterParams& params) {
    return m_uboBuffer.upload(m_device.device(), &params, sizeof(FilterParams));
}

// ============================================================================
// initStaticDescriptors
//
// Writes UBO (binding 4) and Curve SSBO (binding 5) once during init.
// These buffers never change handle — only their data is uploaded later.
// ============================================================================
void VulkanEngine::initStaticDescriptors() {
    VkDevice dev = m_device.device();

    VkDescriptorBufferInfo uboInfo{};
    uboInfo.buffer = m_uboBuffer.buffer();
    uboInfo.offset = 0;
    uboInfo.range = sizeof(FilterParams);

    VkDescriptorBufferInfo curveInfo{};
    curveInfo.buffer = m_curveBuffer.buffer();
    curveInfo.offset = 0;
    curveInfo.range = 4112;

    VkWriteDescriptorSet writes[4]{};

    for (int set = 0; set < 2; set++) {
        int base = set * 2;

        writes[base + 0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 0].dstSet = m_descriptorSets[set];
        writes[base + 0].dstBinding = 4;
        writes[base + 0].descriptorCount = 1;
        writes[base + 0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[base + 0].pBufferInfo = &uboInfo;

        writes[base + 1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 1].dstSet = m_descriptorSets[set];
        writes[base + 1].dstBinding = 5;
        writes[base + 1].descriptorCount = 1;
        writes[base + 1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[base + 1].pBufferInfo = &curveInfo;
    }

    vkUpdateDescriptorSets(dev, 4, writes, 0, nullptr);
}

// ============================================================================
// updateDescriptorSets
//
// Writes bindings 0-3 (storage images + LUT samplers) for both descriptor sets.
// UBO (binding 4) and Curve SSBO (binding 5) are wired once via initStaticDescriptors().
// ============================================================================
void VulkanEngine::updateDescriptorSets() {
    VkDevice dev = m_device.device();

    VkDescriptorImageInfo lut1Info{};
    lut1Info.sampler = m_lutImage[0].sampler();
    lut1Info.imageView = m_lutImage[0].imageView();
    lut1Info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkDescriptorImageInfo lut2Info{};
    lut2Info.sampler = m_lutImage[1].sampler();
    lut2Info.imageView = m_lutImage[1].imageView();
    lut2Info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet writes[8]{};
    VkDescriptorImageInfo imgInfoA[2]{};
    VkDescriptorImageInfo imgInfoB[2]{};

    for (int set = 0; set < 2; set++) {
        int readImg = set;
        int writeImg = 1 - set;
        int base = set * 4;

        // binding 0: input storage image
        imgInfoA[readImg].imageView = m_storageImage[readImg].imageView();
        imgInfoA[readImg].imageLayout = VK_IMAGE_LAYOUT_GENERAL;

        writes[base + 0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 0].dstSet = m_descriptorSets[set];
        writes[base + 0].dstBinding = 0;
        writes[base + 0].descriptorCount = 1;
        writes[base + 0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[base + 0].pImageInfo = &imgInfoA[readImg];

        // binding 1: output storage image
        imgInfoB[writeImg].imageView = m_storageImage[writeImg].imageView();
        imgInfoB[writeImg].imageLayout = VK_IMAGE_LAYOUT_GENERAL;

        writes[base + 1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 1].dstSet = m_descriptorSets[set];
        writes[base + 1].dstBinding = 1;
        writes[base + 1].descriptorCount = 1;
        writes[base + 1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[base + 1].pImageInfo = &imgInfoB[writeImg];

        // binding 2: LUT1
        writes[base + 2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 2].dstSet = m_descriptorSets[set];
        writes[base + 2].dstBinding = 2;
        writes[base + 2].descriptorCount = 1;
        writes[base + 2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[base + 2].pImageInfo = &lut1Info;

        // binding 3: LUT2
        writes[base + 3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 3].dstSet = m_descriptorSets[set];
        writes[base + 3].dstBinding = 3;
        writes[base + 3].descriptorCount = 1;
        writes[base + 3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[base + 3].pImageInfo = &lut2Info;
    }

    vkUpdateDescriptorSets(dev, 8, writes, 0, nullptr);
}

// ============================================================================
// updateLutDescriptors
//
// Writes only LUT samplers (bindings 2-3) for both descriptor sets.
// Used after uploadLut() to avoid rewriting unchanged storage/UBO/SSBO bindings.
// ============================================================================
void VulkanEngine::updateLutDescriptors() {
    VkDevice dev = m_device.device();

    VkDescriptorImageInfo lut1Info{};
    lut1Info.sampler = m_lutImage[0].sampler();
    lut1Info.imageView = m_lutImage[0].imageView();
    lut1Info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkDescriptorImageInfo lut2Info{};
    lut2Info.sampler = m_lutImage[1].sampler();
    lut2Info.imageView = m_lutImage[1].imageView();
    lut2Info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet writes[4]{};

    for (int set = 0; set < 2; set++) {
        int base = set * 2;

        writes[base + 0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 0].dstSet = m_descriptorSets[set];
        writes[base + 0].dstBinding = 2;
        writes[base + 0].descriptorCount = 1;
        writes[base + 0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[base + 0].pImageInfo = &lut1Info;

        writes[base + 1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[base + 1].dstSet = m_descriptorSets[set];
        writes[base + 1].dstBinding = 3;
        writes[base + 1].descriptorCount = 1;
        writes[base + 1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[base + 1].pImageInfo = &lut2Info;
    }

    vkUpdateDescriptorSets(dev, 4, writes, 0, nullptr);
}

// ============================================================================
// ensureImageSize -- (re)create ping-pong storage images + transfer buffer
// ============================================================================
bool VulkanEngine::ensureImageSize(uint32_t width, uint32_t height) {
    if (m_currentWidth == width && m_currentHeight == height) {
        return true;
    }

    VkDevice dev = m_device.device();
    vkDeviceWaitIdle(dev);

    // Destroy old resources
    m_transferBuffer.destroy(dev);
    m_storageImage[0].destroy(dev);
    m_storageImage[1].destroy(dev);

    // Create two RGBA8 storage images for ping-pong
    for (int i = 0; i < 2; i++) {
        if (!m_storageImage[i].createStorageImage2D(m_device, width, height,
                                                     VK_FORMAT_R8G8B8A8_UNORM)) {
            LOGE("Failed to create storage image[%d] %ux%u", i, width, height);
            return false;
        }
    }

    // Create transfer buffer (host-visible) for upload and download.
    // Size = width * height * 4 bytes (RGBA8).
    VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4;
    if (!m_transferBuffer.create(m_device, imageSize,
                                 VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                                 VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                 VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                 VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        LOGE("Failed to create transfer buffer (%zu bytes)", (size_t)imageSize);
        return false;
    }

    // Update descriptor sets to reference the new images
    updateDescriptorSets();

    m_currentWidth = width;
    m_currentHeight = height;

    LOGI("Ping-pong RGBA8 images resized to %ux%u (%zu bytes per image)",
         width, height, (size_t)imageSize);
    return true;
}

// ============================================================================
// uploadLut
// ============================================================================
void VulkanEngine::uploadLut(const float* lutData, uint32_t lutSize, int slot) {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (!m_initialized || slot < 0 || slot > 1) return;

    auto now = []() -> int64_t {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec * 1000000000LL + ts.tv_nsec;
    };
    int64_t t0 = now();

    VkDevice dev = m_device.device();
    vkDeviceWaitIdle(dev);

    int64_t t1 = now();

    // lutData is float[R,G,B] per entry (3 floats), total entries = lutSize^3.
    // Expand to RGBA32F (4 floats per entry) for the 3D texture.
    uint32_t totalEntries = lutSize * lutSize * lutSize;

    std::vector<float> rgbaData(totalEntries * 4);
    for (uint32_t i = 0; i < totalEntries; i++) {
        rgbaData[i * 4 + 0] = lutData[i * 3 + 0];
        rgbaData[i * 4 + 1] = lutData[i * 3 + 1];
        rgbaData[i * 4 + 2] = lutData[i * 3 + 2];
        rgbaData[i * 4 + 3] = 1.0f;
    }

    // Destroy old LUT image if it exists
    m_lutImage[slot].destroy(dev);

    // Try RGBA32F first, fall back to RGBA16F
    VkFormat lutFormat = VK_FORMAT_R32G32B32A32_SFLOAT;
    if (!m_lutImage[slot].createSampledImage3D(m_device, lutSize, lutSize, lutSize, lutFormat)) {
        lutFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        if (!m_lutImage[slot].createSampledImage3D(m_device, lutSize, lutSize, lutSize, lutFormat)) {
            LOGE("Failed to create 3D LUT image (slot %d, size %u)", slot, lutSize);
            return;
        }
    }

    // Prepare upload data in the correct format
    const void* uploadSrc = rgbaData.data();
    VkDeviceSize dstDataSize = static_cast<VkDeviceSize>(totalEntries) * 4 * sizeof(float);
    std::vector<uint16_t> halfData;
    if (lutFormat == VK_FORMAT_R16G16B16A16_SFLOAT) {
        halfData.resize(rgbaData.size());
        for (size_t i = 0; i < rgbaData.size(); i++) {
            halfData[i] = floatToHalf(rgbaData[i]);
        }
        uploadSrc = halfData.data();
        dstDataSize = halfData.size() * sizeof(uint16_t);
    }

    // Create a temporary staging buffer for upload
    VulkanBuffer uploadBuffer;
    if (!uploadBuffer.create(m_device, dstDataSize,
                             VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        LOGE("Failed to create LUT upload buffer");
        return;
    }

    if (!uploadBuffer.upload(dev, uploadSrc, dstDataSize)) {
        LOGE("Failed to upload LUT data to staging buffer");
        uploadBuffer.destroy(dev);
        return;
    }

    // Record and submit copy commands
    VkCommandBuffer cmd = m_cmdPool.beginSingleTimeCommands(m_device);
    if (cmd == VK_NULL_HANDLE) {
        LOGE("Failed to begin command buffer for LUT upload");
        uploadBuffer.destroy(dev);
        return;
    }

    // Transition 3D image: UNDEFINED -> TRANSFER_DST
    m_lutImage[slot].transitionLayout(cmd, VK_IMAGE_LAYOUT_UNDEFINED,
                                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

    // Copy buffer to image — set explicit strides for Mali 3D texture compatibility
    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = lutSize;
    region.bufferImageHeight = lutSize;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {lutSize, lutSize, lutSize};

    vkCmdCopyBufferToImage(cmd, uploadBuffer.buffer(),
                           m_lutImage[slot].image(),
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                           1, &region);

    // Transition: TRANSFER_DST -> SHADER_READ_ONLY
    m_lutImage[slot].transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                       VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

    m_cmdPool.endSingleTimeCommands(m_device, cmd);

    uploadBuffer.destroy(dev);

    m_lutUploaded[slot] = true;

    // Update LUT descriptor bindings only — UBO/SSBO/storage images unchanged
    updateLutDescriptors();

    int64_t t2 = now();
    LOGI("LUT uploaded (slot %d, size %u): idle=%lldms upload=%lldms total=%lldms",
         slot, lutSize,
         (long long)((t1-t0)/1000000), (long long)((t2-t1)/1000000), (long long)((t2-t0)/1000000));
}

// ============================================================================
// uploadCurves -- upload 1024 floats of curve lookup data to SSBO
// ============================================================================
void VulkanEngine::uploadCurves(const float* curveData) {
    if (!m_initialized || curveData == nullptr) return;

    std::lock_guard<std::mutex> lock(m_mutex);
    m_curveBuffer.upload(m_device.device(), curveData, 4112);
    LOGD("Curve data uploaded (1028 floats)");
}

// ============================================================================
// processImage -- main processing entry point
//
// Processes RGBA8 image in-place:
//   1. Upload rgbaData -> storage image[0] (via transfer buffer)
//   2. Dispatch unified shader: read image[0] -> write image[1]
//   3. Copy storage image[1] -> transfer buffer
//   4. Download transfer buffer -> rgbaData
// ============================================================================
bool VulkanEngine::processImage(uint8_t* rgbaData, uint32_t width, uint32_t height,
                                 const FilterParams& params) {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (!m_initialized) {
        LOGE("Engine not initialized");
        return false;
    }

    if (rgbaData == nullptr || width == 0 || height == 0) {
        LOGE("Invalid arguments: rgbaData=%p, width=%u, height=%u", rgbaData, width, height);
        return false;
    }

    auto now = []() -> int64_t {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec * 1000000000LL + ts.tv_nsec;
    };

    VkDevice dev = m_device.device();
    VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4;

    // Ensure storage images and transfer buffer match requested dimensions
    if (!ensureImageSize(width, height)) {
        LOGE("Failed to ensure image size %ux%u", width, height);
        return false;
    }

    int64_t t0 = now();

    // Upload FilterParams to UBO
    FilterParams gpuParams = params;
    gpuParams.width = static_cast<int>(width);
    gpuParams.height = static_cast<int>(height);
    if (!uploadUbo(gpuParams)) {
        LOGE("Failed to upload UBO");
        return false;
    }

    int64_t t1 = now();

    // Upload pixel data to transfer buffer
    if (!m_transferBuffer.upload(dev, rgbaData, imageSize)) {
        LOGE("Failed to upload RGBA8 data to transfer buffer (%ux%u, %zu bytes)",
             width, height, (size_t)imageSize);
        return false;
    }

    int64_t t2 = now();

    // Record command buffer
    VkCommandBuffer cmd = m_cmdPool.beginSingleTimeCommands(m_device);
    if (cmd == VK_NULL_HANDLE) {
        LOGE("Failed to begin command buffer for processing");
        return false;
    }

    // --- Step 1: Copy transfer buffer -> storage image[0] ---
    m_storageImage[0].transitionLayout(cmd, VK_IMAGE_LAYOUT_UNDEFINED,
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

    VkBufferImageCopy uploadRegion{};
    uploadRegion.bufferOffset = 0;
    uploadRegion.bufferRowLength = 0;
    uploadRegion.bufferImageHeight = 0;
    uploadRegion.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    uploadRegion.imageOffset = {0, 0, 0};
    uploadRegion.imageExtent = {width, height, 1};

    vkCmdCopyBufferToImage(cmd, m_transferBuffer.buffer(),
                           m_storageImage[0].image(),
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                           1, &uploadRegion);

    // --- Step 2: Transition image[0] to GENERAL for shader read ---
    m_storageImage[0].transitionLayout(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                        VK_IMAGE_LAYOUT_GENERAL);

    // --- Step 3: Transition image[1] to GENERAL for shader write ---
    m_storageImage[1].transitionLayout(cmd, VK_IMAGE_LAYOUT_UNDEFINED,
                                        VK_IMAGE_LAYOUT_GENERAL);

    // --- Step 4: Dispatch unified compute shader ---
    // Use descriptor set A: reads image[0], writes image[1]
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipeline.pipeline());

    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            m_pipeline.layout(), 0, 1,
                            &m_descriptorSets[0], 0, nullptr);

    uint32_t groupCountX = (width + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X;
    uint32_t groupCountY = (height + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y;
    vkCmdDispatch(cmd, groupCountX, groupCountY, 1);

    // --- Step 5: Transition image[1] to TRANSFER_SRC for readback ---
    m_storageImage[1].transitionLayout(cmd, VK_IMAGE_LAYOUT_GENERAL,
                                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

    // --- Step 6: Copy image[1] -> transfer buffer ---
    VkBufferImageCopy readbackRegion{};
    readbackRegion.bufferOffset = 0;
    readbackRegion.bufferRowLength = 0;
    readbackRegion.bufferImageHeight = 0;
    readbackRegion.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    readbackRegion.imageOffset = {0, 0, 0};
    readbackRegion.imageExtent = {width, height, 1};

    vkCmdCopyImageToBuffer(cmd, m_storageImage[1].image(),
                           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           m_transferBuffer.buffer(),
                           1, &readbackRegion);

    // --- Step 7: Submit and wait ---
    m_cmdPool.endSingleTimeCommands(m_device, cmd);

    int64_t t3 = now();

    // --- Step 8: Download result back to caller's buffer ---
    if (!m_transferBuffer.download(dev, rgbaData, imageSize)) {
        LOGE("Failed to download result from GPU (%ux%u)", width, height);
        return false;
    }

    int64_t t4 = now();

    LOGD("Vulkan processImage %ux%u: ubo=%lldms upload=%lldms gpu=%lldms download=%lldms total=%lldms",
         width, height,
         (long long)((t1-t0)/1000000), (long long)((t2-t1)/1000000),
         (long long)((t3-t2)/1000000), (long long)((t4-t3)/1000000), (long long)((t4-t0)/1000000));

    return true;
}
