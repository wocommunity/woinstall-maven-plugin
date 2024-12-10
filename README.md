**To build**

```mvn clean install```

**To run**

No maven project is required to run the plugin, although you can include it in your project's pom.xml as part of your build process.

```mvn io.github.wocommunity:woinstall-maven-plugin:woinstall```

**What did it just do?**

* Downloaded WebObjects dmg (resumable)
* Verified download with known checksum
* Retain the dmg in case it is ever needed again
* Install the next_root for ant builds from the dmg
* Install the WebObjects jars to your local maven repository from the next_root
* Install a webobjects-bom artifact (Bill of Materials)
* Print out license text and paths to the installed resources

If the installation is already completed, it's basically a no-op. It just prints out the success messages, license, and paths again.

**Options**

You can specify a WebObjects version to install with **installVersion**. Supported values are 5.4.3 and 5.3.3. 5.4.3 is the default.

```mvn io.github.wocommunity:woinstall-maven-plugin:woinstall -DinstallVersion=5.3.3```

You can specify a local maven repository path with **localRepositoryPath**. If this option is not specified, the plugin will just install it to the usual place.

