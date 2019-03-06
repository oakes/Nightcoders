![screenshot](resources/public/screenshot.png)

## Introduction

Nightcoders.net is a cloud IDE for ClojureScript. Do `boot run` for development and `boot build` to make a jar file. You will need JDK 8 or above installed along with the [Boot](http://boot-clj.com/) build tool.

To successfully run it on a server, all you need to do is run that jar file and make sure the `boot.jar` (located at the root of this repo) is in the same directory. It will need JRE 8 or above installed but will not need Boot installed.

That's not a joke...there is literally nothing else you need to do. Just `java -jar nightcoders.jar` and it will run. It uses H2, an embedded database, so there is nothing else to set up. Clojure is dope!

If you want to use your own Google Sign In client id (which you should...), find the existing one in these three files and change them to whatever you want:

* `resources/public/nightcoders.html`
* `resources/public/loading.html`
* `src/clj/nightcoders/core.clj`

Your server must proxy web socket connections for the code reloading to work properly. For example, nginx has [documentation](https://www.nginx.com/blog/websocket-nginx/) on how to do this.

If you are experiencing permissions errors in your control panel, it may be because the sandboxing system isn't working properly. You can disable it by commenting out [this line](https://github.com/oakes/Nightcoders.net/blob/dc2bdf3ab8cd7a93d4f53bc12788e480f1228aef/resources/template.build.boot#L22).

## Licensing

All files that originate from this project are dedicated to the public domain. I would love pull requests, and will assume that they are also dedicated to the public domain.
