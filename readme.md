Paperclip
=========
A binary patch distribution system for Paper.

It uses a [Maven plugin](https://github.com/PaperMC/PaperclipMavenPlugin) to generate a [bsdiff](http://www.daemonology.net/bsdiff/) patch between 
the vanilla server and the modified JAR.
Then it generates a launcher JAR, with the patch and info inside of it.
The launcher downloads the Mojang JAR, verifies it, and applies the patch.
Finally, it wraps the patched JAR in its own class loader, and runs the main class.

This avoids the legal problems of the GPL's linking clause.

The patching overhead is avoided if a valid patched JAR is found in the cache directory.
It checks via sha256 so any modification to those JARs (or updated launcher) will cause a repatch.

Building
--------

Copy the vanilla Minecraft JAR the build is based off of and the Paper JAR into the root directory of this project.
Either name them according to the settings in the pom.xml, or modify it to match the names of the JAR.
To create the launcher for this build, run this command:

`mvn clean package`
