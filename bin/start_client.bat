set "curpath=%cd%"
cd bin
java -Dnohup=true RunFromCMD %curpath% org.ocs.client.QuotaClient
cd ..
