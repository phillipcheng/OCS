#/bin/bash
CL=

for file in ../lib/*
do
	CL=$CL:$file
done
echo $CL

java -cp $CL:../ocs-0.0.1-SNAPSHOT.jar:../resources org.ocs.server.QuotaServer > ocs.log 2>&1 &