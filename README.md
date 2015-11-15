# PlamDriver
An alternate Indi driver for the Inova Pla-M camera

# Installation

Configure i4j server: add this to your .bashrc file
```
export indihome="/home/farom/.i4j/"
export indiHome=$indihome
PATH=$PATH:/home/farom/.i4j/bin
```
Run i4j-server-interactive

Type d ~/.i4j/

Type l ~/.i4j/lib/

Type b ~/.i4j/etc/server.boot if you want to start the driver with the server

Stop the server

Copy PlamDriver-0.0.1-SNAPSHOT-jar-with-dependencies.jar to ~/.i4j/lib/

Create a file ~/.i4j/etc/server.boot which contains 
```
c farom.plamdriver.INDIPlamDriver
```

# Use
Run i4j-server-interactive

Type
```
c farom.plamdriver.INDIPlamDriver
```
if the you want to start the driver manually. 

Type ld to verify that the driver is loaded
