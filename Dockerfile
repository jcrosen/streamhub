FROM clojure
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN lein deps
EXPOSE 19424
