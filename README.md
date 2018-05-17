# zebralink
A Cordova/Phonegap plugin for Zebra bluetooth printers

##This Plugin is Unmaintained 
I no longer work on any projects using Cordova and Zebra printers and this project is essentially abandoned.  It worked for me last I used it (few years ago) and I have taken a few pull requests but I don't have the resources to maintain it.  Anybody interested in adopting this plugin, please contact me and I will gladly transfer ownership.

##Usage
You can send data in ZPL Zebra Programing Language:

###Discover Printers
Search for Zebra bluetooth printer devices.  
```
cordova.plugins.zebralink.discover = function(
	success(printers)
	{
		alert('Found Printers: ' + JSON.stringify(printers));
	}, 
	fail(error)
	{
		alert('Discover failed ' + JSON.stringify(error));
	}, 
	options={range: "172.20.10.*"});


```
###Connect
Options should contain 'printer' set to one of the printers returned from discover.
```
cordova.plugins.zebralink.connect(success(){},fail(error){},options = {printer: printer});
```

###Disconnect
Disconnect the current printer.
```
cordova.plugins.zebralink.disconnect(success(){},fail(error){});
```

###Print
Expects a template and optionally a formValues dictionary of values to substitute into the template. Template variables look like @variable@.
```
cordova.plugins.zebralink.print(success(){},fail(error){},options{template: "^XA^FO10,10^AFN,26,13^FD@message@^FS^XZ" formValues: {message: "Hello, World!"}});
```

###Swipe
Activates the printer's mag card reader until it receives a swipe or times out.  Default timeout is 20 seconds (timeout specified in ms).
```
cordova.plugins.zebralink.swipe(success(){},fail(error){},options={timeout: 20000});
```
##Install
###Cordova

```
cordova plugin add https://github.com/tblanchard/zebralink.git
```


##ZPL - Zebra Programming Language
For more information about ZPL please see the  [PDF Official Manual](https://support.zebra.com/cpws/docs/zpl/zpl_manual.pdf)
