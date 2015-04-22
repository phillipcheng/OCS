set "curpath=%cd%"
cd bin
java -Dnohup=true RunFromCMD %curpath% org.ocs.server.QuotaServer
cd ..
