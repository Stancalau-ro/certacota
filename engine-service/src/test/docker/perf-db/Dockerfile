FROM postgres:17-alpine
ARG ACCOUNTS=1000
ARG TXN_PER_ACCOUNT=100
ENV ACCOUNTS=${ACCOUNTS}
ENV TXN_PER_ACCOUNT=${TXN_PER_ACCOUNT}
ENV POSTGRES_DB=certacota
ENV POSTGRES_USER=certacota
ENV POSTGRES_PASSWORD=certacota
COPY seed.sh /docker-entrypoint-initdb.d/01-seed.sh
RUN chmod +x /docker-entrypoint-initdb.d/01-seed.sh
