# Nuxeo Kubernetes Application for Google Cloud Marketplace

## Overview

### About Nuxeo

Hyland's cloud-native and low-code Nuxeo Platform is a rapid deployment tool for application development and enterprise modernization in the cloud.

[Learn more](https://www.hyland.com/en/solutions/products/nuxeo-platform).

### Kubernetes application

The Nuxeo Kubernetes application for Google Cloud Marketplace relies on the Nuxeo Helm chart, please make sure you read its [documentation](https://github.com/nuxeo/nuxeo-helm-chart/) to start with.

Basically, a Kubernetes Deployment manages all Nuxeo Pods in this application. Each Pod runs a single instance of Nuxeo.

All Pods are behind a Service object. By default, Nuxeo is exposed using a `ClusterIP` Service on port `80`. Optionally, you can expose the service externally by using an Ingress resource. Then, the Nuxeo interface is exposed on ports `80` and `443` . The TLS certificates are stored in the `nuxeo-tls` Secret resource.

## Installation

### Quick install with Google Cloud Marketplace

Get up and running with a few clicks! Install this Nuxeo app to a Google
Kubernetes Engine cluster using Google Cloud Marketplace. Follow the
[on-screen instructions](https://console.cloud.google.com/marketplace/details/hyland/nuxeo).

### Command line instructions

You can use [Google Cloud Shell](https://cloud.google.com/shell/) or a local
workstation to follow the steps below.

#### Prerequisites

##### Set up command-line tools

You'll need the following tools in your development environment. If you are
using Cloud Shell, `gcloud`, `kubectl`, Docker, and Git are installed in your
environment by default.

- [gcloud](https://cloud.google.com/sdk/gcloud/)
- [kubectl](https://kubernetes.io/docs/reference/kubectl/overview/)
- [docker](https://docs.docker.com/install/)
- [git](https://git-scm.com/book/en/v2/Getting-Started-Installing-Git)
- [openssl](https://www.openssl.org/)
- [helm](https://helm.sh/)

Configure `gcloud` as a Docker credential helper:

```shell
gcloud auth configure-docker
```

##### Create a Google Kubernetes Engine (GKE) cluster

Create a new cluster from the command line:

```shell
export CLUSTER=nuxeo-cluster
export ZONE=us-east1

gcloud container clusters create "$CLUSTER" --zone "$ZONE"
```

Configure `kubectl` to connect to the new cluster:

```shell
gcloud container clusters get-credentials "$CLUSTER" --zone "$ZONE"
```

##### Clone this repository

Clone this repository:

```shell
git clone https://github.com/nuxeo/nuxeo-gcp-marketplace.git
```

##### Install the Application resource definition

An Application resource is a collection of individual Kubernetes components,
such as Services, Deployments, and so on, that you can manage as a group.

To set up your cluster to understand Application resources, run the following
command:

```shell
kubectl apply -f "https://raw.githubusercontent.com/GoogleCloudPlatform/marketplace-k8s-app-tools/master/crd/app-crd.yaml"
```

You need to run this command once.

The Application resource is defined by the
[Kubernetes SIG-apps](https://github.com/kubernetes/community/tree/master/sig-apps)
community. The source code can be found on
[github.com/kubernetes-sigs/application](https://github.com/kubernetes-sigs/application).

#### Install the application

Navigate to the `nuxeo-mp` directory:

```shell
cd deployer/chart/nuxeo-mp
```

##### Configure the app with environment variables

Choose the namespace and instance name for the application.

```shell
export NAMESPACE=mynamespace
export APP_INSTANCE=mynuxeo
```

Configure the container image registry:

```shell
export REGISTRY=marketplace.gcr.io/hyland/nuxeo
```

Set up the image tag:

It is advised to use stable image reference which you can find on
[Marketplace Artifact Registry](https://marketplace.gcr.io/hyland/nuxeo).
Example:

```shell
export TAG="1.0"
```

If needed, expose the Service externally and configure Ingress:

```shell
export PUBLIC_SERVICE_AND_INGRESS_ENABLED=true
```

> [!WARNING] Warning about GKE Ingress

When using the built-in Ingress controller provided by Google Kubernetes Engine, [GKE Ingress](https://cloud.google.com/kubernetes-engine/docs/concepts/ingress), the `X-Forwarded-Port` request header is not set. Thus, you need to configure it as a [custom request header](https://cloud.google.com/load-balancing/docs/https/custom-headers#working-with-request).

Alternately, you can:
- Use another Ingress controller such as [NGINX Ingress Controller](https://docs.nginx.com/nginx-ingress-controller/).
- Configure  [Cloud DNS for GKE](https://cloud.google.com/kubernetes-engine/docs/how-to/cloud-dns), in which case you need to set the `virtualHost` parameter of the `nuxeo` subchart to the DNS hostname, see [Install the Helm chart](#install-the-helm-chart).

##### Create TLS certificate for Nuxeo

> [!NOTE]
> You can skip this step if you have not set up external access.

1. If you already have a certificate that you want to use, copy your
   certificate and key pair to the `/tmp/tls.crt`, and `/tmp/tls.key` files,
   then skip to the next step.

   To create a new certificate, run the following command:

   ```shell
   openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
     -keyout /tmp/tls.key \
     -out /tmp/tls.crt \
     -subj "/CN=nuxeo/O=hyland"
   ```

2. Set `TLS_CERTIFICATE_KEY` and `TLS_CERTIFICATE_CRT` variables:

   ```shell
   export TLS_CERTIFICATE_KEY="$(cat /tmp/tls.key | base64)"
   export TLS_CERTIFICATE_CRT="$(cat /tmp/tls.crt | base64)"
   ```

##### Create a namespace in your Kubernetes cluster

Run the command below to create a new namespace:

```shell
kubectl create namespace "$NAMESPACE"
```

##### Install the Helm chart

Update the chart dependencies to fetch the `nuxeo` subchart locally.

```shell
helm dependency update .
```

Use `helm install` to install the application in the target namespace.

```shell
helm install "$APP_INSTANCE" . \
  --namespace "$NAMESPACE" \
  --set nuxeo.image.repository="$REGISTRY" \
  --set nuxeo.image.tag="$TAG" \
  --set nuxeo.ingress.enabled="$PUBLIC_SERVICE_AND_INGRESS_ENABLED" \
  --set tls.base64EncodedPrivateKey="$TLS_CERTIFICATE_KEY" \
  --set tls.base64EncodedCertificate="$TLS_CERTIFICATE_CRT"
```

You can have a look at the complete list of properties of the [nuxeo](https://github.com/nuxeo/nuxeo-helm-chart?tab=readme-ov-file#parameters) subchart.

> [!NOTE]
> Parameters of the `nuxeo` subchart must be prefixed with `nuxeo.` when installing the `nuxeo-mp` parent chart.

##### View the app in the Google Cloud Console

To get the GCP Console URL for your app, run the following command:

```shell
echo "https://console.cloud.google.com/kubernetes/application/$ZONE/$CLUSTER/$NAMESPACE/$APP_INSTANCE"
```

To view the app, open the URL in your browser.

#### Open your Nuxeo site

If the Nuxeo service is exposed externally, get the external IP of your Nuxeo server using the following command:

```shell
SERVICE_IP=$(kubectl get ingress -l "app.kubernetes.io/instance=$APP_INSTANCE" \
  --namespace "$NAMESPACE" \
  --output jsonpath='{.items[].status.loadBalancer.ingress[0].ip}')

echo "https://$SERVICE_IP/"
```

The command shows the URL of your Nuxeo site.

It is also possible to connect to Nuxeo without exposing it to public access, knowing its Service name:

```shell
SERVICE_NAME=$(kubectl get service -l "app.kubernetes.io/instance=$APP_INSTANCE" \
  --namespace "$NAMESPACE" \
  --output custom-columns=":metadata.name" \
  --no-headers)
```

To do this, you can connect from a container inside the Kubernetes cluster using the following hostname: `$SERVICE_NAME.$NAMESPACE.svc.cluster.local`.

You can also use port forwarding by running the following command:

```shell
kubectl port-forward svc/$SERVICE_NAME 8080:80 \
  --namespace "$NAMESPACE"
```

Then, access the Nuxeo server with [http://localhost:8080/](http://localhost:8080/).

#### Print application logs

Run the following command:

```shell
kubectl logs -f -l "app.kubernetes.io/instance=$APP_INSTANCE" \
  --namespace "$NAMESPACE" \
  --prefix --tail -1
```

## Scaling

See the [Architecture](https://github.com/nuxeo/nuxeo-helm-chart/?tab=readme-ov-file#architecture) section of the Nuxeo Helm chart documentation.

## Back up and restore

See the Nuxeo documentation about [Backup and Restore](https://doc.nuxeo.com/nxdoc/backup-and-restore/).

## Upgrade the application

### Prepare the environment

We recommend backing up your data before starting the upgrade.

Note that during the upgrade, your Nuxeo site will be unavailable.

Set your environment variables to match the installation properties:

```shell
export NAMESPACE=mynamespace
export APP_INSTANCE=mynuxeo
export REGISTRY=marketplace.gcr.io/hyland/nuxeo
```

### Upgrade Nuxeo

Set the new image version in an environment variable:

```shell
export TAG="1.1"
```

Upgrade the Helm release with the reference to the new image:

```shell
helm upgrade "$APP_INSTANCE" . \
  --namespace "$NAMESPACE" \
  --set nuxeo.image.repository="$REGISTRY" \
  --set nuxeo.image.tag="$TAG"
```

Monitor the process with the following command:

```shell
kubectl get pods -l "app.kubernetes.io/instance=$APP_INSTANCE" \
  --output go-template='Status={{.status.phase}} Image={{(index .spec.containers 0).image}}' \
  --watch
```

The Pod is terminated, and recreated with a new image for the `nuxeo`
container. After the upgrade is complete, the final state of the Pod is
`Running`, and marked as 1/1 in the `READY` column.

## Uninstall the application

### Using the Google Cloud Platform Console

1. In the GCP Console, open
    [Kubernetes Applications](https://console.cloud.google.com/kubernetes/application).

2. From the list of applications, click **$APP_INSTANCE**.

3. On the Application Details page, click **Delete**.

### Using the command line

#### Prepare the environment

Set your environment variables to match the installation properties:

```shell
export NAMESPACE=mynamespace
export APP_INSTANCE=mynuxeo
```

#### Delete the resources

> **NOTE:** We recommend using a `kubectl` version that is the same as the
> version of your cluster. Using the same versions of `kubectl` and the cluster
> helps avoid unforeseen issues.

To delete the resources, delete the installed Application:

```shell
k delete application "$APP_INSTANCE" \
  --namespace "$NAMESPACE"
```

#### Delete the GKE cluster

Optionally, if you don't need the deployed application or the GKE cluster,
delete the cluster using this command:

```shell
gcloud container clusters delete "$CLUSTER" --zone "$ZONE"
```

## Development

### Prerequisites

You are connected with `glcoud` to a test GKE cluster in a given project, that can be retrieved this way:

```shell
export GCP_PROJECT=$(gcloud config get-value project | tr ':' '/')
```

There is an Artifact Registry in this GCP project, for instance:

```shell
us-docker.pkg.dev/$GCP_PROJECT/nuxeo
```

A Nuxeo image has been pushed to this registry, for instance:

```shell
us-docker.pkg.dev/$GCP_PROJECT/nuxeo/nuxeo:1.0.0
```

You've read the [Tool Prerequisites](https://github.com/GoogleCloudPlatform/marketplace-k8s-app-tools/blob/master/docs/tool-prerequisites.md#tool-prerequisites) documentation.

First, you need to update the chart dependencies to fetch the `nuxeo` subchart locally.

```shell
helm dependency update deployer/chart/nuxeo-mp/
```

### Run the verification tests

Configure the registry and tag of the deployer and tester images:

```shell
export REGISTRY=us-docker.pkg.dev/$GCP_PROJECT/nuxeo
export TAG="1.0.0"
```

Build and push the deployer and tester images, then run the tests:

```shell
docker build --build-arg REGISTRY=$REGISTRY --build-arg TAG=$TAG --tag $REGISTRY/deployer:$TAG deployer \
  && docker push $REGISTRY/deployer:$TAG \
  && docker build --tag $REGISTRY/tester:$TAG tester \
  && docker push $REGISTRY/tester:$TAG \
  && mpdev verify --deployer=$REGISTRY/deployer:$TAG
```

You can have a look at the [Verification system](https://github.com/GoogleCloudPlatform/marketplace-k8s-app-tools/blob/master/docs/verification-integration.md) documentation and the [Dev container references](https://github.com/GoogleCloudPlatform/marketplace-k8s-app-tools/blob/master/docs/mpdev-references.md).

### Deploy a test application

Create a test namespace.

```shell
kubectl create namespace test-namespace
```

Delete the previous test application.

```shell
kubectl delete application test-deployment \
  --namespace=test-namespace \
  --ignore-not-found=true
```

Wait for the `test-deployment-nuxeo` Ingress object to be actually deleted, it can take a while.

You can also delete the deployer Job instead of the whole Application:

```shell
kubectl delete job test-deployment-deployer \
  --namespace=test-namespace \
  --ignore-not-found=true
```

Build the deployer image and install the application in the test namespace.

```shell
docker build --build-arg REGISTRY=$REGISTRY --build-arg TAG=$TAG --tag $REGISTRY/deployer:$TAG deployer \
  && docker push $REGISTRY/deployer:$TAG \
  && mpdev install --deployer=$REGISTRY/deployer:$TAG --parameters='{"name": "test-deployment", "namespace": "test-namespace"}'
```

You can have a look at the [Helm deployer](https://github.com/GoogleCloudPlatform/marketplace-k8s-app-tools/blob/master/docs/building-deployer-helm.md#first-deployment) documentation.

## License

View [license information](https://doc.nuxeo.com/nxdoc/licenses/) for the software contained in the Nuxeo container image.

As with all container images, these likely also contain other software which may be under other licenses (such as Bash, etc from the base distribution, along with any direct or indirect dependencies of the primary software being contained).

As with any pre-built image, it is the user's responsibility to ensure compliance with all relevant licenses for the software included in the image.
