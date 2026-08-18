cmake_minimum_required(VERSION 3.18)
project(skia_jni CXX)

set(CMAKE_CXX_STANDARD 17)

find_package(JNI REQUIRED)

add_library(skia_jni SHARED skia_jni.cpp)

target_include_directories(skia_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${SKIA_INCLUDE_DIR}
)

target_link_directories(skia_jni PRIVATE ${SKIA_LIB_DIR})
target_link_libraries(skia_jni ${SKIA_LIBS} android log)
