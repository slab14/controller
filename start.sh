#!/bin/bash

setupJava(){
    export JAVA_HOME=`type -p javac|xargs readlink -f|xargs dirname|xargs dirname|sed 's/8/11/'`
    export PATH=$PATH:$JAVA_HOME/bin/
    export M2_HOME=/usr/share/maven/
    export M2=$M2_HOME
    export MAVEN_OPTS='-Xmx1048m -XX:MaxPermSize=512m -Xms256m'
    export PATH=$M2:$PATH
}

DIR=$(pwd)
if [[ $DIR == *"IoT_Sec_Gateway"* ]]; then
    DIR=$(echo $DIR | cut -d '/' -f -3)
fi
if [[ $DIR == *"controller"* ]]; then
    DIR=$(echo $DIR | cut -d '/' -f -3)
fi

setupJava

# start ODL                                                                                                                                                                            
cd $DIR/controller/karaf/target/assembly
#sudo -E ./bin/karaf

export JAVA_MAX_MEM=512M
export JAVA_MAX_PERM_MEM=512M

./bin/karaf
