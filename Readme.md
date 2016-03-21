Paperclip
=========
A binary patch distribution system for Paper

It uses a [Maven plugin](https://github.com/PaperMC/PaperclipMavenPlugin) to generate a [bsdiff](http://www.daemonology.net/bsdiff/) patch between 
the vanilla server and the modified jar.
Then it generates a launcher jar, with the patch and info inside of it.
The launcher downloads the Mojang jar, verifies it, and applies the patch.
Finally, it wraps the patched jar in its own class loader, and runs the main class.

This avoids the legal problems of the GPL's linking clause.

The patching overhead is avoided if a valid patched jar is found in the cache directory.
It checks via sha256 so any modification to those jars (or updated launcher) will cause a repatch.

Building
--------

Copy the vanilla Minecraft jar the build is based off of and the Paper jar into the root directory of this project.
Either name them according to the settings in the pom.xml, or modify it to match the names of the jar.
To create the launcher for this build, run this command:

`mvn clean package`
