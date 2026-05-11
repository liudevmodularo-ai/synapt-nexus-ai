cmake_minimum_required(VERSION 3.22.1)
project("synapt_nexus_inference" CXX C)

# ─── C++ Standard ────────────────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── PULSE: ARM NEON + Optimization flags for Cortex-A78 ─────────────────────
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} \
    -O3 \
    -march=armv8.2-a+dotprod+fp16 \
    -mfpu=neon \
    -ffast-math \
    -funroll-loops \
    -fomit-frame-pointer")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} \
    -O3 \
    -march=armv8.2-a+dotprod+fp16 \
    -mfpu=neon \
    -ffast-math")

# ─── llama.cpp submodule ──────────────────────────────────────────────────────
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/third_party/llama.cpp)

set(GGML_OPENMP ON CACHE BOOL "" FORCE)
set(GGML_NEON   ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama)

# ─── OpenMP (threading) ───────────────────────────────────────────────────────
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    message(STATUS "✅ OpenMP found — parallel inference enabled")
endif()

# ─── Our JNI library ─────────────────────────────────────────────────────────
add_library(
    synapt_inference
    SHARED
    synapt_inference.cpp
)

target_include_directories(synapt_inference PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(synapt_inference
    llama
    ggml
    android
    log
    ${OpenMP_CXX_LIBRARIES}
)

# ─── Linker flags ─────────────────────────────────────────────────────────────
target_link_options(synapt_inference PRIVATE
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
)

message(STATUS "🏗️  Synapt Inference Engine — Build configured")
message(STATUS "    Target ABI: ${ANDROID_ABI}")
message(STATUS "    CPU: ARM Cortex-A78 (Dimensity 7300)")
message(STATUS "    NEON: ON | OpenMP: ${OpenMP_CXX_FOUND}")
