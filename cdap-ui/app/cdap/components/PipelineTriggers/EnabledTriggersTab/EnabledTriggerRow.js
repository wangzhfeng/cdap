/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import PropTypes from 'prop-types';

import React, { Component } from 'react';
import IconSVG from 'components/IconSVG';
import PipelineTriggersStore from 'components/PipelineTriggers/store/PipelineTriggersStore';
import {
  disableSchedule,
  getPipelineInfo,
} from 'components/PipelineTriggers/store/PipelineTriggersActionCreator';
import LoadingSVG from 'components/LoadingSVG';
import PayloadConfigModal from 'components/PipelineTriggers/PayloadConfigModal';
import T from 'i18n-react';
import NamespaceStore from 'services/NamespaceStore';

const TRIGGER_PREFIX = 'features.PipelineTriggers';
const PREFIX = `${TRIGGER_PREFIX}.EnabledTriggers`;

export default class EnabledTriggerRow extends Component {
  constructor(props) {
    super(props);

    this.pipelineName = PipelineTriggersStore.getState().triggers.pipelineName;
    this.workflowName = PipelineTriggersStore.getState().triggers.workflowName;
    this.disableTriggerClick = this.disableTriggerClick.bind(this);
  }

  disableTriggerClick() {
    disableSchedule(this.props.schedule, this.pipelineName, this.workflowName);
  }

  renderLoading() {
    return (
      <div className="text-center">
        <LoadingSVG />
      </div>
    );
  }

  renderContent() {
    let { schedule, info, pipelineName } = this.props;

    let namespace = NamespaceStore.getState().selectedNamespace;
    let triggeredPipelineInfo = {
      id: pipelineName,
      namespace,
    };

    let triggeringPipelineInfo = {
      id: schedule.trigger.programId.application,
      namespace: schedule.trigger.programId.namespace,
    };

    let events = schedule.trigger.programStatuses;
    let completed = events.indexOf('COMPLETED') > -1,
      killed = events.indexOf('KILLED') > -1,
      failed = events.indexOf('FAILED') > -1;

    return (
      <div className="row-content">
        <div className="pipeline-description">
          <strong>{T.translate(`${TRIGGER_PREFIX}.description`)}: </strong>
          <span>{info.description}</span>
          <a
            href={`/pipelines/ns/${schedule.trigger.programId.namespace}/view/${schedule.trigger.programId.application}`}
          >
            {T.translate(`${TRIGGER_PREFIX}.viewPipeline`)}
          </a>
        </div>

        <div className="helper-text">
          {T.translate(`${TRIGGER_PREFIX}.helperText`, { pipelineName: this.pipelineName })}
        </div>

        <div className="events-list">
          <div className="checkbox-item">
            <IconSVG name={completed ? 'icon-check-square' : 'icon-square-o'} />
            <span>{T.translate(`${TRIGGER_PREFIX}.Events.COMPLETED`)}</span>
          </div>

          <div className="checkbox-item">
            <IconSVG name={killed ? 'icon-check-square' : 'icon-square-o'} />
            <span>{T.translate(`${TRIGGER_PREFIX}.Events.KILLED`)}</span>
          </div>

          <div className="checkbox-item">
            <IconSVG name={failed ? 'icon-check-square' : 'icon-square-o'} />
            <span>{T.translate(`${TRIGGER_PREFIX}.Events.FAILED`)}</span>
          </div>
        </div>

        <div className="action-buttons-container">
          <button
            className="btn btn-secondary"
            onClick={this.disableTriggerClick}
            data-cy="disable-trigger-btn"
          >
            {T.translate(`${PREFIX}.buttonLabel`)}
          </button>

          <PayloadConfigModal
            triggeringPipelineInfo={triggeringPipelineInfo}
            triggeredPipelineInfo={triggeredPipelineInfo}
            scheduleInfo={schedule}
            disabled={true}
          />
        </div>
      </div>
    );
  }

  render() {
    let { isExpanded, schedule, loading } = this.props;

    if (!isExpanded) {
      return (
        <div
          className="pipeline-triggers-row"
          onClick={getPipelineInfo.bind(null, schedule)}
          data-cy={`${schedule.trigger.programId.application}-collapsed`}
        >
          <div className="caret-container">
            <IconSVG name="icon-caret-right" />
          </div>
          <div className="pipeline-name">{schedule.trigger.programId.application}</div>
          <div className="namespace">{schedule.trigger.programId.namespace}</div>
        </div>
      );
    }

    return (
      <div
        className="pipeline-triggers-expanded-row"
        data-cy={`${schedule.trigger.programId.application}-expanded`}
      >
        <div className="header-row" onClick={getPipelineInfo.bind(null, null)}>
          <div className="caret-container">
            <IconSVG name="icon-caret-down" />
          </div>

          <div className="pipeline-name">{schedule.trigger.programId.application}</div>
          <div className="namespace">{schedule.trigger.programId.namespace}</div>
        </div>

        {loading ? this.renderLoading() : this.renderContent()}
      </div>
    );
  }
}

EnabledTriggerRow.propTypes = {
  isExpanded: PropTypes.bool,
  schedule: PropTypes.object,
  loading: PropTypes.bool,
  info: PropTypes.object,
  pipelineName: PropTypes.string,
};
