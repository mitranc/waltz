import {writable, get} from "svelte/store";
import _ from "lodash";

export let filters = writable([]);
export let filteredAssessments = writable([]);
export let selectedLogicalFlow = writable(null);
export let selectedPhysicalFlow = writable(null);
