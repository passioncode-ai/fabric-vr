cmake_minimum_required(VERSION 3.22.1)
project(fabricvr_whisper C CXX)

# whisper.cpp is vendored as a git submodule pinned to v1.9.4 (see docs study §B).
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "third_party/whisper.cpp is missing. Run: git submodule update --init --recursive")
endif()

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

add_library(fabricvr_whisper SHARED fabricvr_whisper.cpp)
target_compile_options(fabricvr_whisper PRIVATE -O3 -fexceptions)
target_link_libraries(fabricvr_whisper whisper log android)
