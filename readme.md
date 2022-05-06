# Overview

Little script aimed at understanding how the Oak GC is supposed to work.

1. We add a random content file to an Oak repository (on the file system)
2. We check that the file has been properly uploaded as a blob
3. We remove the file
4. We run the GC
5. We check whether the file system blob store has been swept by the GC

Please note that GC estimation is disabled.

# Stackoverflow

Related Stackoverflow question: https://stackoverflow.com/questions/72132489

# How to run the test

Parameters to set:

* `blobStoreStorePath`
* `fileStorePath`
* `testFileSizeInMegabytes`

```
mvn clean compile
export MAVEN_OPTS="-ea" && mvn exec:java -Dexec.cleanupDaemonThreads=false -Dexec.mainClass="com.example.TestOakGarbageCollection" -Dexec.args="--blobStoreStorePath <blob store path> --fileStorePath <file store path> --testFileSizeInMegabytes <megabytes>"
```
