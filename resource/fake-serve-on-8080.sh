#!/bin/bash -e

cd "$(readlink -f "$(dirname "$0")")"

PORT=8080
HTTP_RESPONSE_CODE="${1:-200}"

echo
echo "Starting fake server ..."
echo "    PORT              =$PORT"
echo "    HTTP_RESPONSE_CODE=$HTTP_RESPONSE_CODE"
echo

while true ;
do
    { echo -ne "HTTP/1.0 $HTTP_RESPONSE_CODE OK\r\nContent-Length: $(wc -c < some.file)\r\n\r\n"; cat some.file; } | nc.traditional -l -p "$PORT"
done

