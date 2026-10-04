import type { components, paths } from './schema'

/** Readable aliases for generated contract types. */
type Schemas = components['schemas']

export type TicketSummary = Schemas['TicketSummary']
export type Ticket = Schemas['TicketResponse']
export type TicketPage = Schemas['PageResponseTicketSummary']
export type Comment = Schemas['CommentResponse']
export type AvailableTransition = Schemas['AvailableTransition']
export type Category = Schemas['CategoryResponse']
export type Priority = Ticket['priority']
export type Impact = Ticket['impact']
export type Urgency = Ticket['urgency']
export type TicketType = Ticket['type']
export type TicketListParams = NonNullable<paths['/api/tickets']['get']['parameters']['query']>
export type CreateIncident = Schemas['CreateIncidentRequest']
export type CreateServiceRequest = Schemas['CreateServiceRequestRequest']
export type TransitionRequest = Schemas['TransitionRequest']
