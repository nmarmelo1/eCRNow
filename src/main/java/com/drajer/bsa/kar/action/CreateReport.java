package com.drajer.bsa.kar.action;

import com.drajer.bsa.dao.PublicHealthMessagesDao;
import com.drajer.bsa.ehr.service.EhrQueryService;
import com.drajer.bsa.kar.model.BsaAction;
import com.drajer.bsa.kar.model.FhirQueryFilter;
import com.drajer.bsa.model.BsaTypes;
import com.drajer.bsa.model.BsaTypes.BsaActionStatusType;
import com.drajer.bsa.model.KarProcessingData;
import com.drajer.bsa.model.PublicHealthMessage;
import com.drajer.bsa.utils.BsaServiceUtils;
import com.drajer.ecrapp.util.MDCUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.DocumentReference.DocumentReferenceContentComponent;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateReport extends BsaAction {

  private final Logger logger = LoggerFactory.getLogger(CreateReport.class);

  PublicHealthMessagesDao phDao;

  public PublicHealthMessagesDao getPhDao() {
    return phDao;
  }

  public void setPhDao(PublicHealthMessagesDao phDao) {
    this.phDao = phDao;
  }

  @Override
  public BsaActionStatus process(KarProcessingData data, EhrQueryService ehrService) {

    BsaActionStatus actStatus = new CreateReportStatus();
    actStatus.setActionId(this.getActionId());

    // Check Timing constraints and handle them before we evaluate conditions.
    BsaActionStatusType status = processTimingData(data);

    // Ensure the activity is In-Progress and the Conditions are met.
    if (status != BsaActionStatusType.SCHEDULED) {

      logger.info(
          " Action {} can proceed as it does not have timing information ", this.getActionId());

      Set<Resource> resources = new HashSet<>();

      // Get the default queries.
      Map<String, FhirQueryFilter> queries =
          BsaServiceUtils.getDefaultQueriesForAction(this, data.getKar());

      if (queries != null && !queries.isEmpty()) {

        logger.info(" Found Default/Custom Queries for execution ");

        // Try to execute the queries.
        queries.forEach((key, value) -> ehrService.executeQuery(data, key, value));

      } else {

        // Try to Get the Resources that need to be retrieved using Resource Type since queries are
        // not specified.
        List<DataRequirement> inputRequirements = getInputData();
        ehrService.getFilteredData(data, inputRequirements);
        inputRequirements
            .stream()
            .filter(
                ir ->
                    data.getResourcesById(ir.getId()) != null
                        && !data.getResourcesById(ir.getId()).isEmpty())
            .forEach(ir -> resources.addAll(data.getResourcesById(ir.getId())));
      }

      ehrService.loadJurisdicationData(data);

      // Get the Output Data Requirement to determine the type of bundle to create.
      for (DataRequirement dr : outputData) {

        if (dr.hasProfile()) {

          List<CanonicalType> profiles = dr.getProfile();

          for (CanonicalType ct : profiles) {

            logger.info("Getting Report Creator for: {}", ct);
            ReportCreator rc = ReportCreator.getReportCreator(ct.asStringValue());

            if (rc != null) {

              logger.info("Start creating report");
              Resource output =
                  rc.createReport(
                      data, ehrService, resources, dr.getId(), ct.asStringValue(), this);
              logger.info("Finished creating report");

              if (output != null) {

                logger.info(" Adding Report to output generated {}", output.getId());
                data.addActionOutput(actionId, output);

                logger.info(" Adding Report to output using id {}", dr.getId());

                data.addActionOutputById(dr.getId(), output);

                if (Boolean.TRUE.equals(BsaServiceUtils.hasCdaData(output))) {

                  logger.info("Creating PH message for CDA Data ");
                  createPublicHealthMessageForCda(data, BsaTypes.getActionString(type), output);

                } else {

                  // Save FHIR Data to PH messages
                  logger.info(" ToDO : Add FHIR output to PH Messages ");
                }
              }
            }
          }
        }
      }

      if (Boolean.TRUE.equals(conditionsMet(data, ehrService))) {
        // Execute sub Actions
        executeSubActions(data, ehrService);
        // Execute Related Actions.
        executeRelatedActions(data, ehrService);
      }
      actStatus.setActionStatus(BsaActionStatusType.COMPLETED);

    } else {
      logger.info(
          " Action may execute in future or Conditions not met, can't process further. Setting Action Status : {}",
          status);
      actStatus.setActionStatus(status);
    }

    data.addActionStatus(data.getExecutionSequenceId(), actStatus);
    return actStatus;
  }

  public void createPublicHealthMessageForCda(
      KarProcessingData kd, String actionType, Resource output) {

    List<DocumentReference> docrefs = new ArrayList<>();
    MessageHeader header = BsaServiceUtils.findMessageHeaderAndDocumentReferences(output, docrefs);

    for (DocumentReference docRef : docrefs) {

      logger.info(" Found a document reference that needs to be saved ");
      String fileName =
          logDirectory
              + actionType
              + "_"
              + docRef.getSubject().getReferenceElement().getIdPart()
              + "_"
              + docRef.getId()
              + ".xml";

      if (!docRef.getContent().isEmpty()) {

        DocumentReferenceContentComponent drcc = docRef.getContentFirstRep();

        if (drcc.getAttachment() != null && header != null) {

          Attachment att = drcc.getAttachment();

          String payload = new String(att.getData());

          PublicHealthMessage msg = new PublicHealthMessage();

          // Set context information
          msg.setFhirServerBaseUrl(kd.getNotificationContext().getFhirServerBaseUrl());
          msg.setPatientId(kd.getNotificationContext().getPatientId());
          msg.setNotifiedResourceId(kd.getNotificationContext().getNotificationResourceId());
          msg.setNotifiedResourceType(kd.getNotificationContext().getNotificationResourceType());
          msg.setNotificationId(kd.getNotificationContext().getId().toString());
          msg.setxCorrelationId(kd.getxCorrelationId());
          msg.setxRequestId(kd.getxRequestId());

          if (kd.getNotificationContext()
              .getNotificationResourceType()
              .equals(ResourceType.Encounter.toString())) {
            msg.setEncounterId(msg.getNotifiedResourceId());
          } else {
            msg.setEncounterId("Unknown");
          }

          // Set Message Information
          msg.setSubmittedFhirData(jsonParser.encodeResourceToString(output));
          msg.setSubmittedCdaData(payload);
          msg.setSubmittedMessageType(header.getEventCoding().getCode());
          msg.setSubmittedDataId(docRef.getId());
          msg.setSubmittedMessageId(header.getId());
          msg.setInitiatingAction(actionType);
          msg.setKarUniqueId(kd.getKar().getVersionUniqueId());

          // Update Version and Matched Trigger Status
          msg.setSubmittedVersionNumber(phDao.getMaxVersionId(msg) + 1);
          msg.setTriggerMatchStatus(
              BsaServiceUtils.getEncodedTriggerMatchStatus(kd.getCurrentTriggerMatchStatus()));

          // Create BitSet for MessageStatus and add attribute.

          logger.info(" TODO : Enable saving only by Healthcare Setting ");
          logger.debug("Saving data to file {}", fileName);
          BsaServiceUtils.saveDataToFile(payload, fileName);

          // Also update the payload in the KarProcessingData
          kd.setSubmittedCdaData(payload);

          // Save the data in the table.
          PublicHealthMessage phm = phDao.saveOrUpdate(msg);
          kd.setPhm(phm);
          MDCUtils.addEicrDocId(msg.getSubmittedDataId());
          logger.info(
              "Public Health message created successfully with submittedDataId: {} version: {}",
              msg.getSubmittedDataId(),
              msg.getSubmittedVersionNumber());

        } // attachment not null
        else {
          logger.info(" Document Reference attachement is empty, nothing to save ");
        }
      } // DocRef has content
      else {
        logger.info(" Document Reference does not have any CDA content ");
      }
    } // For all document references.
  }
}
