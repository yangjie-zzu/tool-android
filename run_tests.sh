#!/bin/bash
G="C:/Users/杨杰/.gradle/caches/modules-2/files-2.1"
CP="D:/projects/tool/app/build/tmp/kotlin-classes/pubAppDebugUnitTest"
CP="$CP;D:/projects/tool/app/build/tmp/kotlin-classes/pubAppDebug"
CP="$CP;D:/projects/tool/app/build/intermediates/javac/pubAppDebug/compilePubAppDebugJavaWithJavac/classes"
CP="$CP;$G/org.jsoup/jsoup/1.18.3/fb42d6cc9898ced4b73175eac49c61a40f35325/jsoup-1.18.3.jar"
CP="$CP;$G/org.jetbrains.kotlin/kotlin-stdlib/1.9.20/e58b4816ac517e9cc5df1db051120c63d4cde669/kotlin-stdlib-1.9.20.jar"
CP="$CP;$G/org.jetbrains.kotlin/kotlin-stdlib-jdk8/1.9.20/e2b4d1f475ae0606d063a84fce4dccdb45c7e12a/kotlin-stdlib-jdk8-1.9.20.jar"
CP="$CP;$G/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.8.0-RC/8253bf6e8c713a3dace54493a838e0f8fd3e08fa/kotlinx-serialization-json-jvm-1.8.0-RC.jar"
CP="$CP;$G/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.8.0-RC/268cabd7cb2796083ce3bc1307626fe66ce784fa/kotlinx-serialization-core-jvm-1.8.0-RC.jar"
CP="$CP;$G/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar"
CP="$CP;$G/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar"
CLASSES=$(ls app/build/tmp/kotlin-classes/pubAppDebugUnitTest/com/yukino/tool/module/reader/*Test.class app/build/tmp/kotlin-classes/pubAppDebugUnitTest/com/yukino/tool/*Test.class 2>/dev/null | grep -v '\$' | sed 's|app/build/tmp/kotlin-classes/pubAppDebugUnitTest/||; s|\.class$||; s|/|.|g' | tr '\n' ' ')
"D:/Java/jdk-17.0.7/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | tail -20
