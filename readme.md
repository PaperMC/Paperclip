Paperclip
=========
A binary patch distribution system for Paper.

Paperclip is the launcher for the Paper Minecraft server. It uses a [bsdiff](http://www.daemonology.net/bsdiff/) patch
between the vanilla Minecraft server and the modified Paper server to generate the Paper Minecraft server immediately
upon first run. Once the Paper server is generated it loads the patched jar into Paperclip's own class loader, and runs
the main class.

This avoids the legal problems of the GPL's linking clause.

The patching overhead is avoided if a valid patched jar is found in the cache directory.
It checks via sha256 so any modification to those jars (or updated launcher) will cause a repatch.

Building
--------

Building Paperclip creates a runnable jar, but the jar will not contain the Paperclip config file or patch data. This
project consists simply of the launcher itself, the [paperweight Gradle plugin](https://github.com/PaperMC/paperweight)
generates the patch and config file and inserts it into the jar provided by this project, creating a working runnable jar.
