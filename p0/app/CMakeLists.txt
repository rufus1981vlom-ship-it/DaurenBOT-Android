cmake_minimum_required(VERSION 3.22.1)
project(dauren_bridge LANGUAGES CXX)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libnode.so")

add_library(dauren_bridge SHARED src/main/cpp/bridge.cpp)
target_compile_features(dauren_bridge PRIVATE cxx_std_20)
target_include_directories(dauren_bridge PRIVATE "${CMAKE_SOURCE_DIR}/libnode/include/node")
find_library(android-log log)
target_link_libraries(dauren_bridge PRIVATE libnode ${android-log})
