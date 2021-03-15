# OPC UA Gateway for GraphQL and MQTT

Connect one or more OPC UA servers to the gateway and access the data from the OPC UA servers with a GraphQL or a MQTT client.

![Gateway](doc/Gateway.png)

# Build and Run

It needs [JDK 11](https://openjdk.java.net/projects/jdk/11/).

You can open the project in IntelliJ IDEA IDE and build it there or use grade to build it from command line.

```
> cd app
> gradle run
```

App is a single program with GraphQL, MQTT and the OPC UA connections in one single program. There is also an option to let it run in clustered mode. Then the modules (app-gateway, app-influxdb, app-plc4x, ...) run in separate processes and form a cluster with a distributed communication bus based on [Vert.x](https://vertx.io). With that option the modules can run on different nodes or they can be deployed in a Kubernetes Cluster. Also some options are only available in clustered mode (like plc4x), but if you want a single app with for example plc4x, then you can also include the plc4x parts in the single app, but you have to code it by your own. 

## Configuration

See config.yaml in the app directory for an example how to configure the Gateway. You can pass a configuration file name to the program as the first argument. If no argument is given then config.yaml will be used. If there are any questions about the configuration, please get in touch with me. 

## Example MQTT Topics

### Using the NodeId
Just the value  
> opc/test/node/ns=2;s=ExampleDP_Float.ExampleDP_Arg1   
> opc/test/node/2/ExampleDP_Float.ExampleDP_Arg1   

Value as JSON with timestamp, quality, ...  
> opc/test/node:json/ns=2;s=ExampleDP_Float.ExampleDP_Arg1  
> opc/test/node:json/2/ExampleDP_Float.ExampleDP_Arg1  

Using the browse path instead of the NodeId  
> opc/test/path/**root-node-id**/**browse-name**/**browse-name**/...   

Wildcard "+" can also be used as a browsename
> opc/ua/path:json/ns=1;s=16|Tags/+  

$objects can be used as root node and will be replace with "i=85"
> opc/test/path/$objects/Test/Test00003/float
> opc/test/path/$objects/Test/Test00003/+

Be careful when using wildcards when there are a lot of nodes, it can lead to a lot of browsing round trips  
> opc/test/path/$objects/Test/+/float

## Using PLC4X

You have to start the app-gateway plus app-plc4x. See config.yaml in each folder of the app. Because some PLC4X drivers / plc's do not support subscriptions we have added a simple polling options. (currently only one polling time per connection). 

```
> cd app-gateway
> gradle run  

> cd app-plc4x
cat config.yaml
Plc4x:
  Drivers:    
    - Id: "mod"
      Enabled: true
      Url: "modbus://localhost:502"
      Polling:
        Time: 100  # ms
        OldNew: true  # old new comparions on or off
      LogLevel: ALL

> gradle run
```

Example GraphQL Query:
```
{
  a: NodeValue(
    Type: Plc,
    System: "mod"
    NodeId: "coil:1"
  ) {
    Value
  }
  b: NodeValue(
    Type: Plc
    System: "mod"
    NodeId: "holding-register:1:INT"
  ) {
    Value
    SourceTime
  }  
}
```

Example MQTT Topic:
> plc/mod/node:json/holding-register:1:INT  
> plc/mod/node/holding-register:2:INT  
> plc/mod/node:json/coil:1  
> plc/mod/node/coil:1  

## Enable OPC UA Schema in GraphQL

The GraphQL server can read the OPC UA object schema and convert it to a GraphQL schema. 
```
GraphQLServer:
  Listeners:
    - Port: 4000
      LogLevel: ALL # ALL | INFO
      Schemas: # This systems will be browsed and converted to GraphQL
        - System: ignition # Id of OPC UA system
          FieldName: BrowseName # Use BrowseName or DisplayName as GraphQL item 
        - System: unified
          FieldName: DisplayName # BrowseName | DisplayName
```

You have to enable BrowseOnStartup for the systems which you want to embedd in the GraphQL.

```
OpcUaClient:
  - Id: "winccoa"`  
    Enabled: false`  
    LogLevel: ALL
    EndpointUrl: "opc.tcp://centos1:4840"
    UpdateEndpointUrl: centos1
    SecurityPolicyUri: http://opcfoundation.org/UA/SecurityPolicy#None  
    BrowseOnStartup: true
```

Example GraphQL Query with two OPC UA systems:

```
{
  Systems {
    ignition {
      Tag_Providers {
        default {
          Pump_1 {
            flow { ...Value }
            speed { ...Value }
          }
        }
      }
    }
    unified {
      HmiRuntime {
        HMI_RT_5 {
          Structure_instances{
            A1 {
              Velocity { ...Value }
              RefPoint { ...Value }
            }
          }
        }
      }
    }
  }
}

fragment Value on Node {
  Value {
    Value
    SourceTime
  }
}

```

## Build Docker Image

You have to build the program before with gradle. Then you can use the shell script `docker/build.sh` to build a docker image.  
`docker run --rm --name gateway -p 4000:4000 -p 1883:1883 -v $PWD/config.yaml:/app/config.yaml gateway`
