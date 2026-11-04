FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /iykyk-devices .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /iykyk-devices /iykyk-devices
EXPOSE 8080
ENTRYPOINT ["/iykyk-devices"]
