package software.amazon.transfer.certificate;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

import lombok.NoArgsConstructor;
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.InternalServiceErrorException;
import software.amazon.awssdk.services.transfer.model.InvalidRequestException;
import software.amazon.awssdk.services.transfer.model.ResourceNotFoundException;
import software.amazon.awssdk.services.transfer.model.TagResourceRequest;
import software.amazon.awssdk.services.transfer.model.TransferException;
import software.amazon.awssdk.services.transfer.model.UntagResourceRequest;
import software.amazon.awssdk.services.transfer.model.UpdateCertificateRequest;
import software.amazon.cloudformation.exceptions.CfnGeneralServiceException;
import software.amazon.cloudformation.exceptions.CfnInvalidRequestException;
import software.amazon.cloudformation.exceptions.CfnNotFoundException;
import software.amazon.cloudformation.exceptions.CfnServiceInternalErrorException;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

@NoArgsConstructor
public class UpdateHandler extends BaseHandler<CallbackContext> {
    private TransferClient client;

    public UpdateHandler(TransferClient client) {
        this.client = client;
    }

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {

        if (this.client == null) {
            this.client = ClientBuilder.getClient();
        }
        final ResourceModel model = request.getDesiredResourceState();

        UpdateCertificateRequest updateCertificateRequest = UpdateCertificateRequest.builder()
                .certificateId(model.getCertificateId())
                .description(model.getDescription())
                .activeDate(
                        model.getActiveDate() != null
                                ? Instant.parse(model.getActiveDate())
                                : null)
                .inactiveDate(
                        model.getInactiveDate() != null
                                ? Instant.parse(model.getInactiveDate())
                                : null)
                .build();

        String arn = String.format("arn:%s:transfer:%s:%s:certificate/%s",
                request.getAwsPartition(),
                request.getRegion(),
                request.getAwsAccountId(),
                model.getCertificateId());

        Map<String, String> allDesiredTagsMap = new HashMap<>();
        if (request.getDesiredResourceTags() != null) {
            allDesiredTagsMap.putAll(request.getDesiredResourceTags());
        }
        if (request.getSystemTags() != null) {
            allDesiredTagsMap.putAll(request.getSystemTags());
        }
        model.setTags(Converter.TagConverter.translateTagfromMap(allDesiredTagsMap));

        Set<Tag> previousTags = Converter.TagConverter.translateTagfromMap(request.getPreviousResourceTags());
        Set<Tag> desiredTags = model.getTags();

        Set<Tag> tagsToAdd = Sets.difference(new HashSet<>(desiredTags), new HashSet<>(previousTags));
        Set<Tag> tagsToRemove = Sets.difference(new HashSet<>(previousTags), new HashSet<>(desiredTags));

        try {
            proxy.injectCredentialsAndInvokeV2(updateCertificateRequest, client::updateCertificate);
            logger.log(String.format("%s created successfully", ResourceModel.TYPE_NAME));

            if (!tagsToAdd.isEmpty()) {
                TagResourceRequest tagResourceRequest = TagResourceRequest.builder()
                        .arn(arn)
                        .tags(tagsToAdd.stream().map(Converter.TagConverter::toSdk).collect(Collectors.toList()))
                        .build();
                proxy.injectCredentialsAndInvokeV2(tagResourceRequest, client::tagResource);
            }

            if (!tagsToRemove.isEmpty()) {
                UntagResourceRequest unTagResourceRequest = UntagResourceRequest.builder()
                        .arn(arn)
                        .tagKeys(tagsToRemove.stream().map(Tag::getKey).collect(Collectors.toList()))
                        .build();
                proxy.injectCredentialsAndInvokeV2(unTagResourceRequest, client::untagResource);
            }

            logger.log(String.format("%s %s updated tags successfully",
                    ResourceModel.TYPE_NAME,
                    model.getPrimaryIdentifier()));

            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.SUCCESS)
                    .build();
        } catch (InvalidRequestException e) {
            throw new CfnInvalidRequestException(request.toString(), e);
        } catch (InternalServiceErrorException e) {
            throw new CfnServiceInternalErrorException("Updating tags for certificate", e);
        } catch (ResourceNotFoundException e) {
            throw new CfnNotFoundException(ResourceModel.TYPE_NAME, arn);
        } catch (TransferException e) {
            logger.log(String.format("Failed to update %s", arn));
            throw new CfnGeneralServiceException(e.getMessage(), e);
        }
    }
}
