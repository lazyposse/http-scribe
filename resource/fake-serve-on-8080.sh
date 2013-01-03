#!/bin/bash -e

cd "$(readlink -f "$(dirname "$0")")"

while true ;
do
    { echo -ne "HTTP/1.0 200 OK\r\nContent-Length: $(wc -c < some.file)\r\n\r\n"; cat some.file; } | nc.traditional -l -p 8080
done

