cmake_minimum_required(VERSION 3.22)
project(llama-android C CXX)

set(CMAKE_CXX_STANDARD 17)

# ── llama.cpp source (cloned by setup_llama.sh) ──────────────
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Force STATIC builds so everything links into our single .so
# (Android only packages explicitly declared SHARED libs)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(GGML_STATIC          ON  CACHE BOOL "" FORCE)
set(LLAMA_STATIC         ON  CACHE BOOL "" FORCE)
set(GGML_SHARED          OFF CACHE BOOL "" FORCE)
set(LLAMA_SHARED         OFF CACHE BOOL "" FORCE)

# Suppress "not a git repository" warnings (we strip .git in setup script)
set(LLAMA_BUILD_NUMBER "b8514" CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMIT "local"  CACHE STRING "" FORCE)

# Disable features we don't need
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# Add llama.cpp as a sub-project — builds ggml + llama as static libs
add_subdirectory(${LLAMA_DIR} llama.cpp)

# ── JNI bridge ────────────────────────────────────────────────
add_library(llama-android SHARED llama_jni.cpp)

target_include_directories(llama-android PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llama-android
    llama
    ggml
    android
    log
)
