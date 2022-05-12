# Overview

Little script aimed at understanding how the Oak GC is supposed to work.

1. We add a random content file to an Oak repository (on the file system)
2. We check that the file has been properly uploaded as a blob
3. We remove the file
4. We run the GC
5. We check whether the file system blob store has been swept by the GC

Please note that GC estimation is disabled.

# Main finding

As reported by the Oak developers commetings [the issue I opened](https://issues.apache.org/jira/browse/OAK-9765) regarding this matter, in order to run GC in an effective way, it is required to first run "compaction", this way:

```Java
        for (int k = 0; k < gcOptions.getRetainedGenerations(); k++) {
            fileStore.compactFull();
        }
```

# Stackoverflow

Related Stackoverflow question: https://stackoverflow.com/questions/72132489

# How to run the test

Parameters to set:

* `blobStoreStorePath`
* `fileStorePath`
* `testFileSizeInMegabytes`
* `blobGarbageCollection` - that is either:
    * `LEGACY` - that is, the strategy implemented in OAK
    * `JCR_DATA_SEARCH` - that is, my attempt to find a fix to the hypothetical bug affecting the legacy GC

```
mvn clean compile
export MAVEN_OPTS="-ea" && mvn exec:java -Dexec.cleanupDaemonThreads=false -Dexec.mainClass="com.example.TestOakGarbageCollection" -Dexec.args="--blobStoreStorePath <blob store path> --fileStorePath <file store path> --testFileSizeInMegabytes <megabytes> --blobGarbageCollection [LEGACY|JCR_DATA_SEARCH]"
```
