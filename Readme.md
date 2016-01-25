Paperclip
=========
A binary patch distribution system for PaperSpigot

It uses a [maven plugin](https://github.com/PaperSpigot/PaperclipMavenPlugin) to generate [bsdiff](http://www.daemonology.net/bsdiff/) patches between the vanillla server and the modified jar.
Then it generates a launcher jar, with the patch and info inside of it.
The launcher downloads the mojang jar, verifies it, and applies the patches.
Finally, it wraps the patched jar in its own class loader, and runs the main class.

This avoids the legal problems of the GPL's linking clause.

All of this patching overhead is avoided if a valid patched jar is found in the cache directory.
It checks via sha256 so any modification to those jars (or updated launcher) will cause a rebuild.

There is also a [bash script](https://gist.github.com/Techcable/0b491b70347fd46a9e3f) by @Techcable for those who don't want to use the mvaen plugin,
